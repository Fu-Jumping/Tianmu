package com.tianmu.agent.model;

import com.tianmu.agent.core.ConversationTopics;
import com.tianmu.agent.core.DanmakuTarget;
import com.tianmu.agent.core.RequestContext;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Model-facing page information. The host retains the original page for task resolution and access checks. */
public final class ModelPageContext {
    private static final Pattern PAGE_REFERENCE = Pattern.compile("(?:这个|这条|该|当前(?:的)?|本|现在的)(?:视频|片段|画面|直播间)");
    private static final Pattern PAGE_DATA = Pattern.compile("(?:查|统计|分析|总结|看看|看下|生成|做).{0,16}(?:弹幕|评论|观众反馈|报告)|(?:弹幕|评论).{0,9}(?:数量|多少|几条)");
    private static final Pattern TITLE = Pattern.compile("标题|这个视频叫什么|视频名");

    private ModelPageContext() {}

    public static Map<String, Object> project(RequestContext page, String query) {
        if (page == null) return Map.of();
        boolean playback = ConversationTopics.currentPlayback(query);
        boolean pageReference = PAGE_REFERENCE.matcher(query).find();
        boolean title = TITLE.matcher(query).find();
        var target = DanmakuTarget.matcher(query);
        if (target.find()) {
            if (!target.group(1).equals(page.videoId()) || target.find() || !(playback || title)) return Map.of();
        } else if (!playback && !pageReference
                && (ConversationTopics.general(query)
                    || !(ConversationTopics.videoContent(query) || PAGE_DATA.matcher(query).find()))) {
            return Map.of();
        }
        var environment = new LinkedHashMap<String, Object>();
        environment.put("videoId", page.videoId());
        if (title && !page.videoTitle().isBlank()) environment.put("videoTitle", page.videoTitle());
        if (playback && page.playbackMs() != null) environment.put("playbackMs", page.playbackMs());
        return environment;
    }
}
