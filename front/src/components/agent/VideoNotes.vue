<template>
  <section class="notes-entry" aria-label="视频笔记">
    <button class="entry-button" type="button" @click="open = true">视频笔记 <span>整理全片内容 ↗</span></button>
    <div v-if="open" class="notes-panel" role="dialog" aria-label="视频笔记">
      <header><strong>视频笔记</strong><button type="button" aria-label="关闭视频笔记" @click="open = false">×</button></header>
      <main>
        <p v-if="error" role="alert">{{ error }} <button type="button" @click="refresh">刷新</button></p>
        <template v-else-if="state.status === 'NOT_STARTED'">
          <h2>把整条视频整理成笔记</h2><p>结合视频画面和语音，梳理重点、章节与对应时间。生成需要一些时间。</p>
          <button type="button" :disabled="loading" @click="create">{{ loading ? '正在提交…' : '生成视频笔记' }}</button>
        </template>
        <p v-else-if="active" role="status">正在整理视频内容，完成后可在这里查看。你可以稍后回来。</p>
        <p v-else-if="state.status === 'READY' && !document">正在读取笔记…</p>
        <div v-else-if="state.status && state.status !== 'READY'"><p>本次笔记未能完成。你可以尝试从已完成的进度继续。</p><button v-if="state.status === 'PARTIAL'" type="button" :disabled="loading" @click="retry">继续生成</button></div>
        <article v-if="document">
          <button type="button" @click="copyDocument">{{ copyState || '复制笔记' }}</button>
          <h2>{{ document.title }}</h2><p>{{ document.overview }}</p>
          <h3 v-if="document.keyPoints.length">要点</h3><ul><li v-for="(point, i) in document.keyPoints" :key="i">{{ point }}</li></ul>
          <section v-for="section in document.sections" :key="section.id">
            <h3>{{ section.title }}</h3><button class="timestamp" type="button" @click="seek(section.range.startMs)">{{ time(section.range.startMs) }}–{{ time(section.range.endMs) }} ↗</button>
            <template v-for="(block, i) in section.blocks" :key="i">
              <ul v-if="block.kind === 'LIST'"><li v-for="(item, j) in block.items" :key="j">{{ item }}</li></ul>
              <table v-else-if="block.kind === 'TABLE'"><tr v-for="(row, j) in block.rows" :key="j"><td v-for="(cell, k) in row" :key="k">{{ cell }}</td></tr></table>
              <pre v-else-if="block.kind === 'CODE'">{{ block.text }}</pre>
              <AnswerContent v-else :text="block.kind === 'MATH' ? math(block.text) : block.text" />
            </template>
            <p v-for="(uncertainty, i) in section.uncertainties" :key="i">{{ uncertainty }}</p>
            <details class="sources"><summary>查看本节来源</summary><button v-for="source in sources(section)" :key="source.key" class="timestamp" type="button" @click="seek(source.range.startMs)">{{ source.modality === 'AUDIO' ? '语音' : '画面' }} {{ time(source.range.startMs) }}–{{ time(source.range.endMs) }} ↗</button></details>
          </section>
          <h3 v-if="document.conclusions.length">总结</h3><p v-for="(item, i) in document.conclusions" :key="i">{{ item }}</p>
        </article>
      </main>
    </div>
  </section>
</template>
<script setup>
/* global defineProps */
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { videoNotes } from '@/api/agent'
import { useAssistantStore } from '@/store/assistant'
import AnswerContent from '@/components/agent/AnswerContent.vue'
import { notesMath } from '@/agent/notes-math.mjs'
const props = defineProps({ videoId: { type: String, required: true } })
const assistant = useAssistantStore()
const open = ref(false), loading = ref(false), state = ref({}), document = ref(null), error = ref('')
const copyState = ref('')
const active = computed(() => ['QUEUED', 'RUNNING'].includes(state.value.status))
let controller, timer, generation = 0
function time(ms) { const s = Math.floor(ms / 1000); return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}` }
function math(text) { return notesMath(text) }
function seek(ms) { assistant.seekRequest = { videoId: props.videoId, seconds: Math.floor(ms / 1000) } }
function sources(section) {
  return [...new Map(section.blocks.flatMap(block => block.citations || []).map(source => {
    const key = `${source.unitId}:${source.modality}`
    return [key, { ...source, key }]
  })).values()]
}
async function copyDocument() {
  const value = document.value
  if (!value) return
  const text = [value.title, value.overview, ...value.keyPoints.map(point => `• ${point}`),
    ...value.sections.flatMap(section => [`${section.title} (${time(section.range.startMs)}–${time(section.range.endMs)})`,
      ...section.blocks.map(block => block.kind === 'LIST' ? block.items.map(item => `• ${item}`).join('\n')
        : block.kind === 'TABLE' ? block.rows.map(row => row.join('\t')).join('\n') : block.text), ...section.uncertainties]),
    ...value.conclusions].join('\n\n')
  try { await navigator.clipboard.writeText(text); copyState.value = '已复制' }
  catch { copyState.value = '复制失败，请重试' }
}
async function refresh() {
  clearTimeout(timer); controller?.abort(); controller = new AbortController(); const current = ++generation
  try {
    const value = await videoNotes(props.videoId, 'status', {}, controller.signal)
    if (current !== generation) return
    state.value = value; error.value = ''
    if (value.status === 'READY') {
      const result = await videoNotes(props.videoId, 'document', { jobId: value.jobId }, controller.signal)
      if (current !== generation) return
      document.value = result
    }
    else document.value = null
    if (current === generation && active.value) timer = setTimeout(refresh, 5000)
  } catch (e) { if (current === generation && e.name !== 'AbortError') error.value = e.message }
}
async function create() {
  clearTimeout(timer); controller?.abort(); controller = new AbortController(); const current = ++generation
  loading.value = true; error.value = ''
  try {
    await videoNotes(props.videoId, 'create', { sourceVersion: state.value.sourceVersion, key: crypto.randomUUID() }, controller.signal)
    if (current === generation) await refresh()
  } catch (e) { if (current === generation && e.name !== 'AbortError') error.value = e.message }
  finally { loading.value = false }
}
async function retry() {
  clearTimeout(timer); controller?.abort(); controller = new AbortController(); const current = ++generation
  loading.value = true; error.value = ''
  try { await videoNotes(props.videoId, 'retry', { jobId: state.value.jobId }, controller.signal); if (current === generation) await refresh() }
  catch (e) { if (current === generation && e.name !== 'AbortError') error.value = e.message } finally { loading.value = false }
}
watch(() => props.videoId, () => { state.value = {}; document.value = null; copyState.value = ''; refresh() }, { immediate: true })
onBeforeUnmount(() => { generation++; clearTimeout(timer); controller?.abort() })
</script>
<style scoped>
.notes-entry{padding:8px 24px;border-bottom:1px solid var(--workbench-border);flex-shrink:0}.entry-button{width:100%;border:0;background:transparent;text-align:left;cursor:pointer}.entry-button span{float:right;color:var(--workbench-muted)}.notes-panel{position:absolute;inset:52px 0 0;background:var(--workbench-paper);z-index:8;display:flex;flex-direction:column}.notes-panel header{display:flex;justify-content:space-between;padding:18px 24px;border-bottom:1px solid var(--workbench-border)}.notes-panel button{cursor:pointer}.notes-panel main{overflow:auto;padding:20px 24px;line-height:1.8}.notes-panel h2{font-size:22px}.notes-panel h3{margin-top:28px;font-size:17px}.timestamp{border:0;padding:0;background:transparent;color:var(--workbench-muted);font-size:12px}.notes-panel p,.notes-panel li{white-space:pre-wrap}.notes-panel table{border-collapse:collapse;max-width:100%;display:block;overflow:auto}.notes-panel td{border:1px solid var(--workbench-border);padding:6px}.notes-panel pre{white-space:pre-wrap}
</style>
