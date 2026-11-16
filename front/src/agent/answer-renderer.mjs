import MarkdownIt from 'markdown-it'
import katex from 'katex'
import createDOMPurify from 'dompurify'

const markdown = new MarkdownIt({ html: false, linkify: false, breaks: true })
const escape = markdown.utils.escapeHtml
function math(content, displayMode) {
  try {
    return katex.renderToString(content, { displayMode, throwOnError: true, trust: false, strict: 'error', maxExpand: 1000, maxSize: 20 })
  } catch { return `<code class="math-fallback">${escape(content)}</code>` }
}
markdown.inline.ruler.before('escape', 'answer_math', (state, silent) => {
  const start = state.pos, source = state.src
  const open = source.startsWith('\\(', start) ? '\\(' : source[start] === '$' && source[start + 1] !== '$' ? '$' : null
  if (!open || /\s/.test(source[start + open.length] || ' ')) return false
  const close = open === '$' ? '$' : '\\)'
  let end = source.indexOf(close, start + open.length)
  while (end >= 0 && source[end - 1] === '\\' && close === '$') end = source.indexOf(close, end + close.length)
  if (end < 0 || end === start + open.length || /\s/.test(source[end - 1])) return false
  if (!silent) { const token = state.push('answer_math', 'math', 0); token.content = source.slice(start + open.length, end) }
  state.pos = end + close.length
  return true
})
markdown.renderer.rules.answer_math = (tokens, index) => math(tokens[index].content, false)
markdown.block.ruler.before('fence', 'answer_math_block', (state, start, end, silent) => {
  const first = state.src.slice(state.bMarks[start] + state.tShift[start], state.eMarks[start]).trim()
  const open = first.startsWith('$$') ? '$$' : first.startsWith('\\[') ? '\\[' : null
  if (!open) return false
  const close = open === '$$' ? '$$' : '\\]'
  let body = first.slice(open.length), last = start
  if (body.endsWith(close)) body = body.slice(0, -close.length)
  else {
    let found = false
    for (last = start + 1; last < end; last++) {
      const line = state.src.slice(state.bMarks[last] + state.tShift[last], state.eMarks[last])
      if (line.trimEnd().endsWith(close)) { body += '\n' + line.trimEnd().slice(0, -close.length); found = true; break }
      body += '\n' + line
    }
    if (!found) return false
  }
  if (silent) return true
  const token = state.push('answer_math_block', 'math', 0); token.content = body.trim(); token.map = [start, last + 1]
  state.line = last + 1
  return true
}, { alt: ['paragraph'] })
markdown.renderer.rules.answer_math_block = (tokens, index) => math(tokens[index].content, true)
// Model-supplied images must never trigger remote requests or tracking pixels.
markdown.renderer.rules.image = (tokens, index) => escape(tokens[index].content || '')
markdown.validateLink = href => /^(https?:\/\/|\/(?!\/)|#)/i.test(href)
markdown.renderer.rules.link_open = (tokens, index, options, env, self) => {
  tokens[index].attrSet('target', '_blank'); tokens[index].attrSet('rel', 'noopener noreferrer nofollow')
  return self.renderToken(tokens, index, options)
}

/** Raw HTML is disabled; sanitize the generated HTML and MathML as a final boundary. */
export function createAnswerRenderer(window) {
  const purifier = createDOMPurify(window)
  return value => {
    const text = typeof value === 'string' ? value : ''
    const html = text.length > 64000 ? `<pre>${escape(text)}</pre>` : markdown.render(text)
    return purifier.sanitize(html, { FORBID_TAGS: ['img', 'svg', 'style', 'iframe', 'object', 'embed', 'form'], FORBID_ATTR: ['id', 'name'] })
  }
}
