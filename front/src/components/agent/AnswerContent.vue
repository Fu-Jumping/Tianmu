<template><div class="answer-content" v-html="rendered" /></template>
<script setup>
/* global defineProps */
import { computed } from 'vue'
import { createAnswerRenderer } from '@/agent/answer-renderer.mjs'
import 'katex/dist/katex.min.css'
const props = defineProps({ text: { type: String, default: '' } })
const render = createAnswerRenderer(window)
const rendered = computed(() => render(props.text))
</script>
<style scoped>
.answer-content{white-space:normal;overflow-wrap:anywhere;min-width:0;line-height:1.8}
.answer-content :deep(p){margin:0 0 .8em}.answer-content :deep(p:last-child){margin-bottom:0}
.answer-content :deep(ul),.answer-content :deep(ol){padding-left:1.5em;margin:.6em 0}
.answer-content :deep(h1),.answer-content :deep(h2),.answer-content :deep(h3){font-size:1.08em;line-height:1.5;margin:1em 0 .5em}
.answer-content :deep(pre){white-space:pre-wrap;overflow-wrap:anywhere;padding:12px;background:var(--workbench-raised);border-radius:8px}
.answer-content :deep(code){font-size:.9em}.answer-content :deep(a){color:var(--workbench-accent);text-decoration:underline}
.answer-content :deep(.katex-display){max-width:100%;overflow-x:auto;overflow-y:hidden;padding:4px 0}
.answer-content :deep(.katex){font-size:1.08em}.answer-content :deep(.math-fallback){white-space:pre-wrap}
.answer-content :deep(table){display:block;max-width:100%;overflow-x:auto;border-collapse:collapse}
.answer-content :deep(td),.answer-content :deep(th){padding:6px 10px;border:1px solid var(--workbench-border)}
</style>
