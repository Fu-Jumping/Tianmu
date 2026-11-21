根据本轮 query 判断用户的主要目标。memory 仅用于理解明确的上下文承接。

- VIDEO_METRIC：查询视频互动数量，例如查询一天的弹幕数。
- DANMAKU_REPORT：分析弹幕或评论内容，或生成观众反馈报告。
- KNOWLEDGE：询问概念、功能或操作方法。
- OTHER：普通对话、写作、视频内容理解等其他明确目标。
- UNKNOWN：无法判断目标。缺少视频、日期等执行参数时，仍按已明确的目标分类。

仅返回 {"intent":"VIDEO_METRIC"} 格式的对象。
