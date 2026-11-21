根据本轮 query，从 intentCandidates 中选择用户要完成的目标。memory 仅用于理解明确的上下文承接。

仅返回 {"primary":"conversation","related":[]} 格式的对象。primary 必须是清单中的一个 id，related 最多四项，均为清单中不重复的 id，且不能包含 primary 或 unknown。

选择规则：
- 以用户最终要完成的事情为主目标。「结合画面和弹幕做一份报告」选 audience-report，related 为 video-content 和 danmaku-analysis，仅保留用户明确要求且在清单中的子目标。
- 只查数量选 video-metrics；分析正文选 danmaku-analysis 或 comment-analysis；生成反馈报告选 audience-report；理解镜头、剧情或指定片段选 video-content。
- 查找自己的视频选 video-discovery，查询已有报告选 report-query。
- 要求生成作者评论或弹幕草稿时选择对应智能草稿能力；普通写作、闲聊、整理想法选 conversation。
- 询问功能或操作方法选 knowledge，例如「怎么分析弹幕？」。要求实际分析，如「分析一下这个视频的弹幕」，选 danmaku-analysis。
- 即使缺少视频、日期或来源，目标明确时仍选对应能力。目标不明或能力不在清单中时，返回 {"primary":"unknown","related":[]}。

- 当前视频上的泛指「生成个报告」，优先选择清单中的 audience-report，其次选 video-content。
