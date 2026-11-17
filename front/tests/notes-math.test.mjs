import test from 'node:test'
import assert from 'node:assert/strict'
import { JSDOM } from 'jsdom'
import { notesMath } from '../src/agent/notes-math.mjs'
import { createAnswerRenderer } from '../src/agent/answer-renderer.mjs'
const window = new JSDOM('').window
const render = createAnswerRenderer(window)
test('renders mixed prose and uniformly double-escaped formulas from a model', () => {
  const root = window.document.createElement('div')
  root.innerHTML = render(notesMath(String.raw`设 X 为集合，\\forall x \\in X，\\exists! y \\in Y。`))
  assert.ok(root.querySelector('.katex'))
  assert.equal(root.querySelector('.math-fallback'), null)
})
test('preserves valid matrix row separators and existing text commands', () => {
  const source = String.raw`\begin{matrix}a\\b\end{matrix}\quad\text{为单射}`
  assert.equal(notesMath(source), `$$\n${source}\n$$`)
})
