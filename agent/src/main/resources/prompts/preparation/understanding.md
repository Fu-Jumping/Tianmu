根据本轮 query 判断是否需要澄清。memory 仅用于理解明确的上下文承接，不能把历史中的视频或日期猜成本轮条件。

沿用 serverTaskCandidate.conditions 中已解析的条件和默认值，检查它们是否与本轮要求冲突。只追问仍然缺失的必要条件、无法选定的目标、矛盾的约束或确需用户确认的操作。serverTaskCandidate.requiresClarification 为 true 时，提出一个简短、具体的问题。

仅返回以下结构：
{"needsClarification":false,"clarificationQuestion":null,"conditions":{},"rewrittenQuery":null,"issue":"NONE","missingFields":[]}

- 需要澄清时，needsClarification=true，在 clarificationQuestion 中写问题，conditions={}，rewrittenQuery=null。缺少必要字段用 MISSING_FIELDS，多个目标无法确定用 AMBIGUOUS_TARGET，要求互相矛盾用 CONTRADICTORY_CONSTRAINTS，任务目标不明用 UNKNOWN_GOAL，必要确认用 CONFIRMATION_REQUIRED。仅在 MISSING_FIELDS 时列出实际缺少的字段，允许值为 video_id、report_type、sources、time_range、date、metric、start、end、playback_range、output_style。
- 无需澄清时，needsClarification=false，clarificationQuestion=null，issue=NONE，missingFields=[]。conditions 只使用已解析的条件和本轮请求明确支持的标量值。
- 普通聊天、功能咨询、写作和通用建议无需业务数据条件，conditions={}。resource.lookup 可以先查找候选资源，无需为了开始查找而索要唯一视频 ID。unclassified 本身不代表缺条件或存在冲突。
- 对实际数据请求，按目标区分所需条件。视频 ID、直播间 ID、videoId 指同一标识。单日弹幕数量查询用 video_id、date（YYYY-MM-DD）、metric（bullet_count）。区间弹幕数量查询、反馈分析和报告用 video_id、start、end（北京时间 ISO LocalDateTime）。用 businessDate 解释「昨天」，一天的范围截止到次日零点。可选的报告重点或样式无需阻塞执行。
- 视频内容理解只需要视频对象，无需统计日期。明确指定播放区间时，start_ms 和 end_ms 成对使用整数毫秒，最多 120 秒。区间不明确时追问，保留用户指定的范围。
- rewrittenQuery 默认返回 null。仅在指代或口语确需整理，且能在 120 字符内保留原有数字、相对日期、范围、来源和操作限制时，才给出改写。宿主提供的默认值只填入 conditions。
