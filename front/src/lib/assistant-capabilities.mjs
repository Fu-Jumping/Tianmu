import { ref, watch } from 'vue'

// Presentation only. Every operation must still be authorized by the server.
export function watchAssistantCapabilities({ videoId, accountKey, isLogin, load, onRefresh = () => {} }) {
  const readable = ref(false), creator = ref(false), checking = ref(false), error = ref('')
  let generation = 0
  async function refresh() {
    const current = ++generation
    readable.value = false
    creator.value = false
    error.value = ''
    checking.value = isLogin.value
    onRefresh()
    if (!isLogin.value) return
    try {
      const result = await load(videoId.value)
      if (current !== generation) return
      readable.value = !!videoId.value && result.videoContext === true
      creator.value = readable.value && result.creatorTools === true
    } catch (cause) {
      if (current === generation) error.value = cause?.message || '暂时无法确认助手权限，请重试。'
    } finally {
      if (current === generation) checking.value = false
    }
  }
  const unwatch = watch([videoId, accountKey, isLogin], refresh, { immediate: true, flush: 'sync' })
  function stop() { ++generation; unwatch() }
  return { readable, creator, checking, error, refresh, stop }
}
