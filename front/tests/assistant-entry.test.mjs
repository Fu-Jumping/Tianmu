import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import { parse } from '@babel/parser'

test('video card opens the assistant for viewers and authors', () => {
  const source = fs.readFileSync(new URL('../src/views/detail/VideoDetail.vue', import.meta.url), 'utf8')
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
  const tree = parse(script, { sourceType: 'module' })
  const options = tree.program.body.find(n => n.type === 'ExportDefaultDeclaration').declaration
  const methods = options.properties.find(n => n.key.name === 'methods').value
  const method = methods.properties.find(n => n.key?.name === 'openAssistant')
  let opened = 0
  const open = new Function('useAssistantStore', `return ({${script.slice(method.start, method.end)}}).openAssistant`)(() => ({ show() { opened++ } }))
  for (const isVideoAuthor of [false, true]) {
    let query
    open.call({ isVideoAuthor, $route: { query: { tab: 'comments' } }, $router: { replace(value) { query = value.query } } })
    assert.equal(query.assistant, '1')
    assert.equal(query.tab, 'comments')
  }
  assert.equal(opened, 2)
  assert.match(source, /<section class="operations-entry" aria-label="个人助理">/)
})

test('floating launcher is only on the home page while the host remains available', () => {
  const source = fs.readFileSync(new URL('../src/components/agent/AssistantHost.vue', import.meta.url), 'utf8')
  assert.match(source, /v-if="isHome && !visible"[^>]*class="assistant-launcher"/)
  assert.match(source, /v-show="visible" class="assistant-host"/)
})
