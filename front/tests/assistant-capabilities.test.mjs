import test from 'node:test'
import assert from 'node:assert/strict'
import { computed, ref } from 'vue'
import { watchAssistantCapabilities } from '../src/lib/assistant-capabilities.mjs'
import { assistantSuggestionGroups } from '../src/agent/suggestions.mjs'

function fixture() {
  const videoId = ref('1001'), accountKey = ref('alice:token1'), isLogin = ref(true), requests = []
  const capabilities = watchAssistantCapabilities({ videoId, accountKey, isLogin, load(id) {
    return new Promise((resolve, reject) => requests.push({ id, resolve, reject }))
  } })
  const groups = computed(() => assistantSuggestionGroups({ videoId: capabilities.readable.value ? videoId.value : '', creator: capabilities.creator.value, pending: capabilities.checking.value || !!capabilities.error.value }))
  return { videoId, accountKey, isLogin, requests, groups, ...capabilities }
}
const settle = async () => { await Promise.resolve(); await Promise.resolve() }
const author = { videoContext: true, creatorTools: true }
const viewer = { videoContext: true, creatorTools: false }

test('author receives public functions plus exactly three author functions; viewer keeps public functions', async () => {
  const f = fixture()
  try {
    assert.deepEqual(f.groups.value, [])
    f.requests[0].resolve(author); await settle()
    assert.deepEqual(f.groups.value.map(g => g.id), ['public', 'creator'])
    assert.deepEqual(f.groups.value[1].items.map(i => i.title), ['观众反馈报告', '智能评论', '智能弹幕'])
    f.videoId.value = '1002'
    assert.equal(f.creator.value, false)
    assert.equal(f.readable.value, false)
    assert.deepEqual(f.groups.value, [])
    f.requests[1].resolve(viewer); await settle()
    assert.deepEqual(f.groups.value.map(g => g.id), ['public'])
  } finally { f.stop() }
})

test('late video and account responses cannot restore old privileges', async () => {
  const f = fixture()
  try {
    f.videoId.value = '1002'
    f.accountKey.value = 'bob:token2'
    f.requests[2].resolve(viewer); await settle()
    f.requests[0].resolve(author); f.requests[1].resolve(author); await settle()
    assert.equal(f.creator.value, false)
    assert.equal(f.readable.value, true)
    assert.deepEqual(f.groups.value.map(g => g.id), ['public'])
  } finally { f.stop() }
})

test('account switching clears granted capabilities immediately; logout ignores in-flight responses', async () => {
  const f = fixture()
  try {
    f.requests[0].resolve(author); await settle()
    f.accountKey.value = 'bob:token2'
    assert.equal(f.creator.value, false)
    assert.deepEqual(f.groups.value, [])
    f.isLogin.value = false
    f.requests[1].resolve(author); await settle()
    assert.equal(f.creator.value, false)
    assert.equal(f.readable.value, false)
    assert.equal(f.checking.value, false)
    assert.equal(f.requests.length, 2)
  } finally { f.stop() }
})

test('permission failure closes capabilities; retry can recover without stale error overwrites', async () => {
  const f = fixture()
  try {
    f.requests[0].reject(new Error('暂时不可用')); await settle()
    assert.deepEqual(f.groups.value, [])
    assert.equal(f.creator.value, false)
    const retry = f.refresh()
    f.requests[1].resolve(author); await retry
    f.accountKey.value = 'alice:rotated-token'
    assert.deepEqual(f.groups.value, [])
    f.videoId.value = '1002'
    f.requests[3].resolve(viewer); await settle()
    f.requests[2].reject(new Error('old credential')); await settle()
    assert.equal(f.error.value, '')
    assert.deepEqual(f.groups.value.map(g => g.id), ['public'])
  } finally { f.stop() }
})

test('unreadable videos and no-video pages expose only general chat; creator flag alone grants nothing', async () => {
  const f = fixture()
  try {
    f.requests[0].resolve({ videoContext: false, creatorTools: true }); await settle()
    assert.equal(f.creator.value, false)
    assert.deepEqual(f.groups.value.map(g => g.id), ['general'])
    f.videoId.value = ''
    f.requests[1].resolve(author); await settle()
    assert.equal(f.readable.value, false)
    assert.equal(f.creator.value, false)
    assert.deepEqual(f.groups.value.map(g => g.id), ['general'])
  } finally { f.stop() }
})

test('unmount invalidates pending permission responses', async () => {
  const f = fixture()
  f.stop()
  f.requests[0].resolve(author); await settle()
  assert.equal(f.creator.value, false)
  assert.equal(f.readable.value, false)
})
