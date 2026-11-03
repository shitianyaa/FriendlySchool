package com.yiran.friendlyschool.targets;

import java.util.ArrayList;
import java.util.List;

/**
 * 底栏标签裁剪的判定策略（纯 JVM，无 Android 依赖，可离线单测）。
 *
 * 三道判据按强度排序，任一命中即删：
 *   ① 标题命中 —— 每个 tab 项携带的全部字符串（tab id 与显示名）里出现目标文案
 *      （"学习" / "我的"）。这是全 dex 唯一的字符串常量，R8 不动；
 *   ② Fragment 类名 ∈ removedTabs —— 每版都被 R8 重排，只作兜底；
 *   ③ 靠右两项 —— 前两条全落空时启用，要求调用方已确认宿主是 ScheduleActivity。
 *
 * 为什么标题判据传的是"候选字符串集合"而不是单一标题：WakeUp 6.7.0 的
 * {@code PagerAdapter#getPageTitle(int)} 直接返回 null（底栏标题由
 * MainAiTitleTabView 自己从 tab 项对象上读），所以只能把 tab 项里所有 String
 * 字段值都拿来比对。tab 项形如 {@code ("learn", "学习", Fragment)}，两个字符串都在。
 *
 * 长度 &lt; 3 一律不动：底栏至少是「课表 / 日程 / 学习」三项。位置兜底额外要求 ≥ 4：
 * 只有 3 项时"靠右两项"占三分之二，误删风险大于收益。
 */
public final class TabTrimPolicy {

    private TabTrimPolicy() {
    }

    /**
     * 决定要删除的下标（升序）。
     *
     * @param titleCandidates 每个 tab 的候选字符串（tab id + 显示名），元素可为 null
     * @param fragments       每个 tab 的 Fragment 类名，元素可为 null；长度可与 titleCandidates 不同
     * @param removedTitles   要删的标题文案，null 表示该判据不启用
     * @param removedTabs     要删的 Fragment 类名，null 表示该判据不启用
     * @param allowPositionFallback 前两条全落空时是否按"靠右两项"兜底
     * @return 待删下标数组；空数组表示不动
     */
    public static int[] decide(List<List<String>> titleCandidates, List<String> fragments,
                               String[] removedTitles, String[] removedTabs,
                               boolean allowPositionFallback) {
        int n = titleCandidates == null ? 0 : titleCandidates.size();
        if (n < 3) {
            return new int[0];
        }
        List<Integer> hit = new ArrayList<Integer>();
        for (int i = 0; i < n; i++) {
            List<String> cands = titleCandidates.get(i);
            String frag = fragments != null && i < fragments.size() ? fragments.get(i) : null;
            if (anyMatch(removedTitles, cands) || contains(removedTabs, frag)) {
                hit.add(i);
            }
        }
        if (hit.isEmpty() && allowPositionFallback && n >= 4) {
            hit.add(n - 2);
            hit.add(n - 1);
        }
        int[] out = new int[hit.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = hit.get(i);
        }
        return out;
    }

    /** 该 tab 的任一候选字符串命中目标文案。 */
    private static boolean anyMatch(String[] targets, List<String> candidates) {
        if (targets == null || candidates == null) {
            return false;
        }
        for (String cand : candidates) {
            if (contains(targets, cand)) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(String[] pool, String value) {
        if (pool == null || value == null) {
            return false;
        }
        for (String s : pool) {
            if (value.equals(s)) {
                return true;
            }
        }
        return false;
    }
}
