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
}

