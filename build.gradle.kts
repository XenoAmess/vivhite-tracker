// Top-level build file
plugins {
    id("com.android.application") version "9.4.1" apply false
    // Room 需要注解处理；版本与 AGP 9.3.1 内置 Kotlin 2.3.x 匹配
    id("com.google.devtools.ksp") version "2.3.12" apply false
}
