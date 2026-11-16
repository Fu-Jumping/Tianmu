import test from 'node:test'
import assert from 'node:assert/strict'
import { JSDOM } from 'jsdom'
import { createAnswerRenderer } from '../src/agent/answer-renderer.mjs'
const window = new JSDOM('').window
const render = createAnswerRenderer(window)
function dom(text) { const root = window.document.createElement('div'); root.innerHTML = render(text); return root }

test('renders emphasis, lists and both inline and display formulae', () => {
  const root = dom('**定义域**\n\n- 集合 $X$\n- 元素 \\(x\\)\n\n\\[\nD_f=\\{1,2,3\\}\n\\]')
  assert.equal(root.querySelector('strong').textContent, '定义域')
  assert.equal(root.querySelectorAll('li').length, 2)
  assert.equal(root.querySelectorAll('.katex').length, 3)
  assert.ok(root.querySelector('math'))
  assert.doesNotMatch(root.textContent, /\*\*定义域/)
})
test('never enables raw HTML, remote images or unsafe links', () => {
  const root = dom('<img src=x onerror=alert(1)>\n<script>alert(1)</script>\n![tracker](https://evil.test/pixel)\n[bad](javascript:alert(1))\n[ok](https://example.org)')
  assert.equal(root.querySelectorAll('script,img,iframe').length, 0)
  assert.equal(root.querySelectorAll('a').length, 1)
  assert.equal(root.querySelector('a').getAttribute('rel'), 'noopener noreferrer nofollow')
})
test('untrusted formula commands and broken formulae have safe fallbacks', () => {
  const root = dom('$\\href{javascript:alert(1)}{x}$\n\n$$\\unknown{a}$$\n\n价格是 $5，代码 `**x**`，未闭合 \\(x')
  assert.equal(root.querySelectorAll('a,img,script').length, 0)
  assert.match(root.textContent, /价格是 \$5/)
  assert.match(root.querySelector('code').textContent, /\\unknown/)
})
