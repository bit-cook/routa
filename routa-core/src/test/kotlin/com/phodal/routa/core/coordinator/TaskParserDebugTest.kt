package com.phodal.routa.core.coordinator

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskParserDebugTest {

    @Test
    fun `test parse user provided ROUTA output with 3 tasks`() {
        val routaOutput = """
我来分析这个请求并制定计划。

## 分析
用户要求：
1. **检查当前代码变更** - 查看当前工作区的代码状态和未提交的更改
2. **reset 代码** - 将代码重置到某个干净状态（可能是最近提交、特定提交或完全干净状态）

这是一个两阶段操作：先检查状态，然后根据检查结果决定如何重置。

## 计划

### 阶段 1: 代码状态检查
首先需要了解当前工作区的状态：
- 是否有未提交的更改？
- 是否有未跟踪的文件？
- 当前在哪个分支？
- 最近的提交是什么？

### 阶段 2: 代码重置
根据检查结果：
1. 如果有未提交的更改，询问用户如何处理（丢弃更改、暂存等）
2. 执行适当的重置操作

由于用户没有指定重置到什么状态，我需要：
1. 先检查当前状态
2. 向用户报告状态并询问重置目标（如：重置到最新提交、完全干净状态等）
3. 根据用户指示执行重置

## 任务分解

@@@task
# 任务 1: 检查当前代码状态

## Objective
检查当前工作区的Git状态，了解代码变更情况

## Scope
- 运行git status命令查看工作区状态
- 运行git log查看最近提交历史
- 检查是否有未提交的更改、未跟踪的文件
- 检查当前分支信息

## Definition of Done
- 获取完整的git status输出
- 获取最近5条提交历史
- 识别所有未提交的更改（修改、新增、删除的文件）
- 识别所有未跟踪的文件
- 报告当前分支和远程跟踪状态

## Verification
- 运行: `git status`
- 运行: `git log --oneline -5`
- 运行: `git diff --name-status` (如果有暂存或未暂存的更改)
- 报告所有发现的状态信息
@@@

@@@task
# 任务 2: 分析重置选项并获取用户确认

## Objective
基于代码状态分析，向用户展示重置选项并获取明确指示

## Scope
- 分析任务1的结果
- 准备不同的重置方案：
  1. 丢弃所有未提交的更改（git reset --hard）
  2. 仅取消暂存的更改（git reset）
  3. 清理未跟踪的文件（git clean）
  4. 重置到特定提交
- 向用户展示当前状态和推荐的重置方案

## Definition of Done
- 清晰展示当前代码状态摘要
- 提供2-3个合理的重置选项
- 等待用户选择具体的重置目标
- 获取用户对重置操作的明确确认

## Verification
- 准备状态摘要报告
- 列出可行的重置选项
- 报告等待用户确认的状态
@@@

@@@task
# 任务 3: 执行代码重置

## Objective
根据用户选择的选项执行代码重置操作

## Scope
- 执行用户选择的git重置命令
- 验证重置操作是否成功
- 确保没有意外数据丢失（如有需要，先备份）

## Definition of Done
- 成功执行用户指定的重置命令
- 验证重置后的工作区状态
- 确认所有指定的更改已被重置
- 报告重置操作的结果

## Verification
- 运行用户指定的重置命令（如：`git reset --hard HEAD`）
- 运行: `git status` 验证重置结果
- 运行: `git log --oneline -3` 验证提交历史
- 报告重置操作是否成功完成
@@@

## 执行策略
1. 首先执行**任务1**检查当前状态
2. 根据状态结果，执行**任务2**向用户报告并获取确认
3. 获得用户确认后，执行**任务3**进行重置

**注意**：任务2需要用户交互，因此我会在执行任务1后暂停，向用户报告状态并等待指示。

---

请确认这个计划是否合适。如果同意，我将开始执行任务1。
        """.trimIndent()

        val tasks = TaskParser.parse(routaOutput, "test-workspace")

        println("Parsed ${tasks.size} tasks:")
        tasks.forEachIndexed { index, task ->
            println("Task ${index + 1}: ${task.title}")
            println("  Objective: ${task.objective.take(50)}...")
            println("  Scope: ${task.scope.size} items")
            println("  Acceptance Criteria: ${task.acceptanceCriteria.size} items")
            println()
        }

        assertEquals("Should parse 3 tasks from ROUTA output", 3, tasks.size)
        assertEquals("任务 1: 检查当前代码状态", tasks[0].title)
        assertEquals("任务 2: 分析重置选项并获取用户确认", tasks[1].title)
        assertEquals("任务 3: 执行代码重置", tasks[2].title)
    }

    @Test
    fun `test parse 5 tasks with Chinese section headers`() {
        val routaOutput = """
我来分析这个请求并制定计划。

## 分析
用户请求"添加中文翻译"，这是一个相对简单的任务，但需要明确翻译的范围和具体内容。

```python
import os
print("test")
```

现在我将制定详细的翻译计划：

@@@task
# 任务1：分析项目结构并识别需要翻译的内容

## 目标
全面分析项目结构，识别所有需要添加中文翻译的文件和内容

## 范围
- 扫描整个项目目录结构
- 识别可能包含需要翻译内容的文件类型
- 检查现有的国际化(i18n)配置（如果有）
- 识别硬编码的英文文本

## 完成标准
- 提供完整的项目结构报告
- 列出所有需要翻译的文件和内容类型
- 识别现有的国际化框架（如果有）
- 提供翻译工作的范围和规模评估

## 验证
- 运行目录扫描命令
- 检查i18n配置文件（如果存在）
- 报告发现的关键文件和内容
@@@

@@@task
# 任务2：设置国际化框架（如果需要）

## 目标
如果项目没有现有的国际化框架，设置一个合适的i18n解决方案

## 范围
- 评估项目技术栈
- 选择合适的i18n库
- 配置基本的国际化设置
- 创建语言文件结构

## 完成标准
- 国际化框架已正确安装和配置
- 创建了基本的语言文件结构
- 配置了语言切换的基础设施（如果需要）
- 更新了项目配置以支持多语言

## 验证
- 检查package.json中i18n库的安装
- 验证i18n配置文件
- 测试基本的语言切换功能
@@@

@@@task
# 任务3：提取和翻译现有英文内容

## 目标
提取所有硬编码的英文文本并翻译成中文

## 范围
- 使用i18n工具或手动提取英文文本
- 创建翻译键值对
- 将英文内容翻译成准确的中文
- 保持上下文和UI布局的一致性

## 完成标准
- 所有识别出的英文文本都已提取
- 创建了完整的中文翻译文件
- 翻译准确且符合上下文
- UI布局适应了中文字符长度

## 验证
- 运行提取脚本（如果使用自动化工具）
- 检查翻译文件的完整性和准确性
- 验证UI显示中文内容
@@@

@@@task
# 任务4：更新UI组件以使用翻译

## 目标
修改UI组件，使用国际化框架显示中文内容

## 范围
- 更新组件中的硬编码文本为翻译键
- 确保动态内容也能正确翻译
- 处理复数形式和变量插值
- 测试所有组件的翻译显示

## 完成标准
- 所有组件都使用翻译键而非硬编码文本
- 动态内容能正确显示中文
- 特殊格式（日期、货币、复数）正确处理
- 没有遗漏任何文本元素

## 验证
- 运行应用并切换语言
- 检查所有页面和组件的显示
- 测试边界情况和特殊字符
@@@

@@@task
# 任务5：测试和验证中文翻译

## 目标
全面测试中文翻译的质量和功能完整性

## 范围
- 功能测试：确保所有功能正常工作
- 语言测试：验证翻译准确性和一致性
- UI测试：检查布局和显示问题
- 回归测试：确保没有破坏现有功能

## 完成标准
- 所有功能在中文环境下正常工作
- 翻译准确、一致且符合上下文
- UI布局适应中文字符，没有截断或重叠
- 没有引入新的bug或问题

## 验证
- 运行完整的测试套件
- 手动测试关键用户流程
- 检查控制台错误和警告
- 验证响应式设计在不同屏幕尺寸下的表现
@@@

## 总体计划总结

**目标**：为项目添加完整的中文翻译支持

**建议**：从任务1开始，根据分析结果调整后续任务。
        """.trimIndent()

        val tasks = TaskParser.parse(routaOutput, "test-workspace")

        println("Parsed ${tasks.size} tasks:")
        tasks.forEachIndexed { index, task ->
            println("Task ${index + 1}: ${task.title}")
            println("  Objective: '${task.objective}'")
            println("  Scope: ${task.scope.size} items")
            println("  Acceptance Criteria: ${task.acceptanceCriteria.size} items")
            println("  Verification: ${task.verificationCommands.size} items")
            println()
        }

        assertEquals("Should parse 5 tasks from ROUTA output", 5, tasks.size)
        assertEquals("任务1：分析项目结构并识别需要翻译的内容", tasks[0].title)
        assertEquals("任务2：设置国际化框架（如果需要）", tasks[1].title)
        assertEquals("任务3：提取和翻译现有英文内容", tasks[2].title)
        assertEquals("任务4：更新UI组件以使用翻译", tasks[3].title)
        assertEquals("任务5：测试和验证中文翻译", tasks[4].title)
    }
}

