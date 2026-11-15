const general = [
  { icon: 'question', title: '你能帮我做什么', description: '先了解助手的能力', text: '你能做什么？' },
  { icon: 'list', title: '整理思路', description: '把想法变成清晰的步骤', text: '我想学习一项新技能，你可以怎么帮助我制定计划？' },
  { icon: 'info', title: '解释概念', description: '用容易理解的例子说明', text: '用日常生活的例子解释什么是机器学习。' },
  { icon: 'route', title: '制定计划', description: '按时间和目标安排步骤', text: '帮我制定每天二十分钟的摄影入门学习计划。' },
]
const publicVideo = [
  { icon: 'play', title: '概括视频', description: '根据可用画面了解内容', text: '概括这个视频的画面内容。' },
  { icon: 'clock', title: '解释当前片段', description: '结合当前播放位置提问', text: '解释这个视频当前播放位置附近的画面。' },
]
const creatorVideo = [
  { icon: 'list', title: '观众反馈报告', description: '整理弹幕与评论，查看主要话题和观众原话', text: '请分析这个视频全部的弹幕和评论，结合实际视频画面生成观众反馈报告，包含主要话题、观众原话和改进建议。' },
  { icon: 'stage', title: '智能评论', description: '生成作者评论草稿，确认后再发送', text: '请为这个视频生成3条智能评论草稿，以作者身份补充说明或引导讨论，先给我预览。' },
  { icon: 'play', title: '智能弹幕', description: '结合画面起草，核对内容与时间点', text: '请为这个视频生成5条智能弹幕草稿，以作者身份自然引导互动，标明播放时间点，先给我预览。' },
]

export function assistantSuggestionGroups({ videoId, creator = false, pending = false }) {
  if (pending) return []
  if (!videoId) return [{ id: 'general', title: '', items: general }]
  const groups = [{ id: 'public', title: '视频理解', items: publicVideo }]
  if (creator) groups.push({ id: 'creator', title: '作者专属', items: creatorVideo })
  return groups
}
