package com.bilibili.livemonitor.domain

import kotlin.random.Random

/**
 * 名言选取算法（纯函数，可单测）：
 * 1. 首次启动必出邓煜「有哪些优秀的百合同人作品？」
 * 2. 之后每次固定 1/10 概率出白绮「孤独是我的冠冕，无需谁来注解。」（不进常规池，概率精确）
 * 3. 其余从常规池（含邓煜）随机，防连续重复
 */
object QuotePicker {

    fun pick(
        isFirstLaunchDone: Boolean,
        lastIndex: Int?,
        random: Random = Random.Default
    ): MathQuotes.MathQuote {
        if (!isFirstLaunchDone) {
            return MathQuotes.SPECIAL_FIRST_LAUNCH
        }
        if (random.nextInt(HIGH_FREQ_DENOMINATOR) == 0) {
            return MathQuotes.SPECIAL_HIGH_FREQ
        }
        val index = MathQuotes.randomExcept(lastIndex, random)
        return MathQuotes.pool[index]
    }

    // 常规路径选中后需要记住下标以防重复；特殊条目返回 null 表示不占用池下标
    fun poolIndexOf(quote: MathQuotes.MathQuote): Int? {
        val index = MathQuotes.pool.indexOf(quote)
        return if (index >= 0) index else null
    }

    const val HIGH_FREQ_DENOMINATOR = 10
}
