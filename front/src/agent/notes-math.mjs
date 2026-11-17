export function notesMath(text) {
  let value = text.trim()
  // Correct a uniformly double-escaped model string without changing valid matrix row breaks.
  if (!/(?<!\\)\\[A-Za-z]+/.test(value)) value = value.replace(/\\\\(?=[A-Za-z])/g, '\\')
  value = value.replace(/\\text\{[^{}]*\}|[\u3400-\u9fff，。；：]+/g, part => part.startsWith('\\text{') ? part : `\\text{${part}}`)
  return value.startsWith('$$') || value.startsWith('\\[') ? value : `$$\n${value}\n$$`
}
