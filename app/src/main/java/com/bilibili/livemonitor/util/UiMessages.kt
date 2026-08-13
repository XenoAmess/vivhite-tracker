package com.bilibili.livemonitor.util

/**
 * 用户可见文案的统一出口（网络失败提示口径全 App 一致）。
 * 更新检查的「无法连接 GitHub」属特定语义文案，不在此列（有测试钉住）。
 */
object UiMessages {

    /** 网络类失败统一提示（ toast 用） */
    const val NETWORK_ERROR = "网络不给力，请稍后再试"

    /** 手账数据加载失败统一提示 */
    const val DATA_LOAD_ERROR = "数据加载失败，请稍后再试"
}
