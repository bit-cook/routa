package com.phodal.routa.core.coordinator

import org.junit.Assert.*
import org.junit.Test

/**
 * Comprehensive tests for [TaskParser].
 *
 * Covers:
 * - Multiple properly delimited @@@task blocks (English headers)
 * - Multiple properly delimited @@@task blocks (Chinese headers)
 * - Single @@@task block containing multiple # titles (fallback split)
 * - Mixed English/Chinese section headers
 * - Edge cases (no tasks, missing sections, empty blocks, etc.)
 */
class TaskParserTest {

    private val workspace = "test-workspace"

    // ── Multiple properly delimited @@@task blocks ──────────────────────

    @Test
    fun `parse multiple tasks with English section headers`() {
        val text = """
Some intro text from ROUTA.

@@@task
# Implement user login

## Objective
Create a login form with email and password.

## Scope
- src/components/LoginForm.tsx
- src/api/auth.ts

## Definition of Done
- User can log in with email and password
- Error messages are displayed for invalid credentials

## Verification
- npm test
- Manual testing of login flow
@@@

@@@task
# Add session management

## Objective
Store JWT token and manage user sessions.

## Scope
- src/utils/session.ts
- src/middleware/auth.ts

## Definition of Done
- JWT is stored securely
- Session expires after 24 hours

## Verification
- npm test
- Check cookie settings in browser
@@@

Some trailing text.
        """.trimIndent()

        val tasks = TaskParser.parse(text, workspace)

        assertEquals(2, tasks.size)

        // Task 1
        assertEquals("Implement user login", tasks[0].title)
        assertEquals("Create a login form with email and password.", tasks[0].objective)
        assertEquals(2, tasks[0].scope.size)
        assertEquals("src/components/LoginForm.tsx", tasks[0].scope[0])
        assertEquals(2, tasks[0].acceptanceCriteria.size)
        assertEquals(2, tasks[0].verificationCommands.size)
        assertEquals("npm test", tasks[0].verificationCommands[0])

        // Task 2
        assertEquals("Add session management", tasks[1].title)
        assertEquals("Store JWT token and manage user sessions.", tasks[1].objective)
        assertEquals(2, tasks[1].scope.size)
        assertEquals(2, tasks[1].acceptanceCriteria.size)
    }

    @Test
    fun `parse multiple tasks with Chinese section headers`() {
        val text = """
我来分析这个请求并制定计划。

@@@task
# 任务1：分析项目结构

## 目标
全面分析项目结构，识别需要修改的文件

## 范围
- 扫描整个项目目录
- 检查现有配置文件

## 完成标准
- 提供完整的项目结构报告
- 列出所有需要修改的文件

## 验证
- 运行目录扫描命令
- 检查配置文件
@@@

@@@task
# 任务2：实现功能

## 目标
根据分析结果实现所需功能

## 范围
- 修改核心模块
- 更新测试用例

## 完成标准
- 所有功能正常工作
- 测试通过

## 验证
- 运行测试套件
- 手动验证功能
@@@
        """.trimIndent()

        val tasks = TaskParser.parse(text, workspace)

        assertEquals(2, tasks.size)

        // Task 1 — Chinese headers
        assertEquals("任务1：分析项目结构", tasks[0].title)
        assertEquals("全面分析项目结构，识别需要修改的文件", tasks[0].objective)
        assertEquals(2, tasks[0].scope.size)
        assertEquals("扫描整个项目目录", tasks[0].scope[0])
        assertEquals(2, tasks[0].acceptanceCriteria.size)
        assertEquals("提供完整的项目结构报告", tasks[0].acceptanceCriteria[0])
        assertEquals(2, tasks[0].verificationCommands.size)
        assertEquals("运行目录扫描命令", tasks[0].verificationCommands[0])

        // Task 2
        assertEquals("任务2：实现功能", tasks[1].title)
        assertEquals("根据分析结果实现所需功能", tasks[1].objective)
    }

    // ── Single block with multiple # titles (fallback split) ────────────

    @Test
    fun `parse single block containing multiple tasks splits correctly`() {
        val text = """
@@@task
# 任务1：分析项目结构

## 目标
分析项目

## 范围
- 扫描目录

## 完成标准
- 完成报告

## 验证
- 运行扫描

# 任务2：实现功能

## 目标
实现功能

## 范围
- 修改代码

## 完成标准
- 功能正常

## 验证
- 运行测试

# 任务3：编写测试

## 目标
覆盖测试

## 范围
- 新增测试文件

## 完成标准
- 覆盖率达标

## 验证
- 运行测试
@@@
        """.trimIndent()

        val tasks = TaskParser.parse(text, workspace)

        assertEquals("Should split single block into 3 tasks", 3, tasks.size)
        assertEquals("任务1：分析项目结构", tasks[0].title)
        assertEquals("任务2：实现功能", tasks[1].title)
        assertEquals("任务3：编写测试", tasks[2].title)

        // Verify sections are extracted for each sub-task
        assertEquals("分析项目", tasks[0].objective)
        assertEquals(1, tasks[0].scope.size)
        assertEquals("实现功能", tasks[1].objective)
        assertEquals("覆盖测试", tasks[2].objective)
    }

    @Test
    fun `parse single block with 5 tasks splits correctly`() {
        val text = """
@@@task
# 任务1：分析项目结构并识别需要翻译的内容

## 目标
全面分析项目结构

## 范围
- 扫描目录
- 检查配置

## 完成标准
- 项目结构报告
- 文件列表

## 验证
- 运行扫描

# 任务2：设置国际化框架

## 目标
设置i18n

## 范围
- 评估技术栈

## 完成标准
- 框架安装

## 验证
- 检查配置

# 任务3：提取和翻译内容

## 目标
翻译英文文本

## 范围
- 提取文本

## 完成标准
- 翻译完成

## 验证
- 检查翻译

# 任务4：更新UI组件

## 目标
使用翻译键

## 范围
- 更新组件

## 完成标准
- 组件更新

## 验证
- 检查UI

# 任务5：测试和验证中文翻译

## 目标
全面测试

## 范围
- 功能测试
- UI测试

## 完成标准
- 测试通过

## 验证
- 运行测试套件
@@@
        """.trimIndent()

        val tasks = TaskParser.parse(text, workspace)

        assertEquals("Should split single block into 5 tasks", 5, tasks.size)
        assertEquals("任务1：分析项目结构并识别需要翻译的内容", tasks[0].title)
        assertEquals("任务2：设置国际化框架", tasks[1].title)
        assertEquals("任务3：提取和翻译内容", tasks[2].title)
        assertEquals("任务4：更新UI组件", tasks[3].title)
        assertEquals("任务5：测试和验证中文翻译", tasks[4].title)

        // Verify first task has full content
        assertEquals("全面分析项目结构", tasks[0].objective)
        assertEquals(2, tasks[0].scope.size)
        assertEquals(2, tasks[0].acceptanceCriteria.size)

        // Verify last task
        assertEquals("全面测试", tasks[4].objective)
        assertEquals(2, tasks[4].scope.size)
    }

    // ── Five properly delimited tasks (user's exact scenario) ────────────

    @Test
    fun `parse 5 properly delimited tasks with Chinese headers`() {
        val routaOutput = """
我来分析这个请求并制定计划。

## 分析
用户请求"添加中文翻译"

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
- 检查现有的国际化配置
- 识别硬编码的英文文本

## 完成标准
- 提供完整的项目结构报告
- 列出所有需要翻译的文件和内容类型
- 识别现有的国际化框架
- 提供翻译工作的范围和规模评估

## 验证
- 运行目录扫描命令
- 检查i18n配置文件
- 报告发现的关键文件和内容
@@@

@@@task
# 任务2：设置国际化框架

## 目标
设置一个合适的i18n解决方案

## 范围
- 评估项目技术栈
- 选择合适的i18n库
- 配置基本的国际化设置
- 创建语言文件结构

## 完成标准
- 国际化框架已正确安装和配置
- 创建了基本的语言文件结构
- 更新了项目配置以支持多语言

## 验证
- 检查i18n库的安装
- 验证i18n配置文件
@@@

@@@task
# 任务3：提取和翻译现有英文内容

## 目标
提取所有硬编码的英文文本并翻译成中文

## 范围
- 使用i18n工具或手动提取英文文本
- 创建翻译键值对
- 将英文内容翻译成准确的中文

## 完成标准
- 所有识别出的英文文本都已提取
- 创建了完整的中文翻译文件
- 翻译准确且符合上下文

## 验证
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

## 完成标准
- 所有组件都使用翻译键而非硬编码文本
- 动态内容能正确显示中文

## 验证
- 运行应用并切换语言
- 检查所有页面和组件的显示
@@@

@@@task
# 任务5：测试和验证中文翻译

## 目标
全面测试中文翻译的质量和功能完整性

## 范围
- 功能测试：确保所有功能正常工作
- 语言测试：验证翻译准确性和一致性
- UI测试：检查布局和显示问题

## 完成标准
- 所有功能在中文环境下正常工作
- 翻译准确、一致且符合上下文
- UI布局适应中文字符

## 验证
- 运行完整的测试套件
- 手动测试关键用户流程
- 检查控制台错误和警告
@@@

## 总体计划总结

**建议**：从任务1开始，根据分析结果调整后续任务。
        """.trimIndent()

        val tasks = TaskParser.parse(routaOutput, workspace)

        assertEquals(5, tasks.size)
        assertEquals("任务1：分析项目结构并识别需要翻译的内容", tasks[0].title)
        assertEquals("任务2：设置国际化框架", tasks[1].title)
        assertEquals("任务3：提取和翻译现有英文内容", tasks[2].title)
        assertEquals("任务4：更新UI组件以使用翻译", tasks[3].title)
        assertEquals("任务5：测试和验证中文翻译", tasks[4].title)

        // Verify Chinese section headers are extracted
        assertEquals("全面分析项目结构，识别所有需要添加中文翻译的文件和内容", tasks[0].objective)
        assertEquals(4, tasks[0].scope.size)
        assertEquals(4, tasks[0].acceptanceCriteria.size)
        assertEquals(3, tasks[0].verificationCommands.size)

        // Verify all tasks have content
        tasks.forEach { task ->
            assertTrue("Task '${task.title}' should have an objective", task.objective.isNotEmpty())
            assertTrue("Task '${task.title}' should have scope", task.scope.isNotEmpty())
            assertTrue("Task '${task.title}' should have acceptance criteria", task.acceptanceCriteria.isNotEmpty())
            assertTrue("Task '${task.title}' should have verification commands", task.verificationCommands.isNotEmpty())
        }
    }

    // ── Mixed header languages ──────────────────────────────────────────

    @Test
    fun `parse task with mixed English and Chinese headers`() {
        val text = """
@@@task
# Refactor authentication

## Objective
Improve the auth flow

## 范围
- src/auth/login.kt
- src/auth/session.kt

## Definition of Done
- Auth flow is simplified
- All tests pass

## 验证
- ./gradlew test
@@@
        """.trimIndent()

        val tasks = TaskParser.parse(text, workspace)

        assertEquals(1, tasks.size)
        assertEquals("Refactor authentication", tasks[0].title)
        assertEquals("Improve the auth flow", tasks[0].objective)
        assertEquals(2, tasks[0].scope.size)
        assertEquals("src/auth/login.kt", tasks[0].scope[0])
        assertEquals(2, tasks[0].acceptanceCriteria.size)
        assertEquals(1, tasks[0].verificationCommands.size)
        assertEquals("./gradlew test", tasks[0].verificationCommands[0])
    }

    // ── Edge cases ──────────────────────────────────────────────────────

    @Test
    fun `parse returns empty list when no task blocks found`() {
        val text = "Just some text without any task blocks."
        val tasks = TaskParser.parse(text, workspace)
        assertTrue(tasks.isEmpty())
    }

    @Test
    fun `parse handles task with no section headers`() {
        val text = """
@@@task
# Simple task

Just a description with no structured sections.
@@@
        """.trimIndent()

        val tasks = TaskParser.parse(text, workspace)

        assertEquals(1, tasks.size)
        assertEquals("Simple task", tasks[0].title)
        assertEquals("", tasks[0].objective)
        assertTrue(tasks[0].scope.isEmpty())
    }

    @Test
    fun `parse handles task with title only`() {
        val text = """
@@@task
# Minimal task
@@@
        """.trimIndent()

        val tasks = TaskParser.parse(text, workspace)

        assertEquals(1, tasks.size)
        assertEquals("Minimal task", tasks[0].title)
    }

    @Test
    fun `parse handles task without title`() {
        val text = """
@@@task
## Objective
Do something
@@@
        """.trimIndent()

        val tasks = TaskParser.parse(text, workspace)

        assertEquals(1, tasks.size)
        assertEquals("Untitled Task", tasks[0].title)
        assertEquals("Do something", tasks[0].objective)
    }

    @Test
    fun `parse with Chinese acceptance criteria alias 验收标准`() {
        val text = """
@@@task
# 测试任务

## 目标
测试验收标准别名

## 验收标准
- 标准一
- 标准二

## 验证
- 运行测试
@@@
        """.trimIndent()

        val tasks = TaskParser.parse(text, workspace)

        assertEquals(1, tasks.size)
        assertEquals(2, tasks[0].acceptanceCriteria.size)
        assertEquals("标准一", tasks[0].acceptanceCriteria[0])
        assertEquals("标准二", tasks[0].acceptanceCriteria[1])
    }

    @Test
    fun `parse preserves task metadata fields`() {
        val text = """
@@@task
# Test task

## Objective
Test objective
@@@
        """.trimIndent()

        val tasks = TaskParser.parse(text, workspace)

        assertEquals(1, tasks.size)
        assertEquals(workspace, tasks[0].workspaceId)
        assertNotNull(tasks[0].id)
        assertTrue(tasks[0].id.isNotEmpty())
        assertEquals(com.phodal.routa.core.model.TaskStatus.PENDING, tasks[0].status)
        assertNotNull(tasks[0].createdAt)
        assertNotNull(tasks[0].updatedAt)
    }

    @Test
    fun `parse tasks back to back without blank lines`() {
        val text = """
@@@task
# Task A

## Objective
Do A
@@@
@@@task
# Task B

## Objective
Do B
@@@
        """.trimIndent()

        val tasks = TaskParser.parse(text, workspace)

        assertEquals(2, tasks.size)
        assertEquals("Task A", tasks[0].title)
        assertEquals("Task B", tasks[1].title)
    }

    // ── Code fence handling (bash comments should NOT be task titles) ─────

    @Test
    fun `parse ignores bash comments inside code fences in verification`() {
        val text = """
@@@task
# Create QUICKSTART.md

## Objective
Create a streamlined QUICKSTART.md guide

## Scope
- QUICKSTART.md in project root

## Definition of Done
- QUICKSTART.md exists
- Contains working examples

## Verification
```bash
# File exists and is valid markdown
ls -la QUICKSTART.md
head -50 QUICKSTART.md

# Check links work (if any relative links)
grep -E "\]\(" QUICKSTART.md | head -10
```
@@@

@@@task
# Create TypeScript Example

## Objective
Create a comprehensive TypeScript example

## Scope
- examples/typescript/ directory

## Definition of Done
- Contains runnable TypeScript code

## Verification
```bash
# Check directory structure
ls -la examples/typescript/

# Install and run (if dependencies allow)
cd examples/typescript && npm install && npm run build
```
@@@
        """.trimIndent()

        val tasks = TaskParser.parse(text, workspace)

        // Should be exactly 2 tasks, not 6
        assertEquals("Bash comments in code fences should NOT create extra tasks", 2, tasks.size)
        assertEquals("Create QUICKSTART.md", tasks[0].title)
        assertEquals("Create TypeScript Example", tasks[1].title)
    }

    @Test
    fun `splitMultiTaskBlock ignores hash lines inside code fences`() {
        val block = """
# Real Task Title

## Objective
Do something

## Verification
```bash
# This is a bash comment, NOT a task title
ls -la
# Another bash comment
echo "hello"
```
        """.trimIndent()

        val result = TaskParser.splitMultiTaskBlock(block)
        assertEquals("Should be 1 task, bash comments ignored", 1, result.size)
        assertTrue(result[0].startsWith("# Real Task Title"))
    }

    @Test
    fun `splitMultiTaskBlock handles multiple tasks with code fences`() {
        val block = """
# Task A

## Verification
```
# Not a task
echo test
```

# Task B

## Verification
```bash
# Also not a task
ls -la
```
        """.trimIndent()

        val result = TaskParser.splitMultiTaskBlock(block)
        assertEquals("Should split into 2 tasks", 2, result.size)
        assertTrue(result[0].startsWith("# Task A"))
        assertTrue(result[1].startsWith("# Task B"))
    }

    // ── splitMultiTaskBlock unit tests ───────────────────────────────────

    @Test
    fun `splitMultiTaskBlock returns single block as-is when one title`() {
        val block = """
# Single task

## Objective
Something
        """.trimIndent()

        val result = TaskParser.splitMultiTaskBlock(block)
        assertEquals(1, result.size)
        assertEquals(block, result[0])
    }

    @Test
    fun `splitMultiTaskBlock splits block with two titles`() {
        val block = """
# Task A

## Objective
Do A

# Task B

## Objective
Do B
        """.trimIndent()

        val result = TaskParser.splitMultiTaskBlock(block)
        assertEquals(2, result.size)
        assertTrue(result[0].startsWith("# Task A"))
        assertTrue(result[1].startsWith("# Task B"))
        assertTrue(result[0].contains("Do A"))
        assertTrue(result[1].contains("Do B"))
    }

    @Test
    fun `splitMultiTaskBlock does not confuse ## headers with # titles`() {
        val block = """
# Only title

## Section One
Content one

## Section Two
Content two
        """.trimIndent()

        val result = TaskParser.splitMultiTaskBlock(block)
        assertEquals(1, result.size)
    }

    @Test
    fun `splitMultiTaskBlock handles block with no titles`() {
        val block = """
Just some content without any headers.
        """.trimIndent()

        val result = TaskParser.splitMultiTaskBlock(block)
        assertEquals(1, result.size)
    }

    // ── Existing test case (backward compatibility) ─────────────────────

    @Test
    fun `parse 3 tasks from ROUTA output with English headers`() {
        val routaOutput = """
我来分析这个请求并制定计划。

## 分析
用户要求检查代码变更并重置代码。

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
- 识别所有未提交的更改
- 识别所有未跟踪的文件
- 报告当前分支和远程跟踪状态

## Verification
- 运行: git status
- 运行: git log --oneline -5
- 运行: git diff --name-status
- 报告所有发现的状态信息
@@@

@@@task
# 任务 2: 分析重置选项并获取用户确认

## Objective
基于代码状态分析，向用户展示重置选项并获取明确指示

## Scope
- 分析任务1的结果
- 准备不同的重置方案
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
- 确保没有意外数据丢失

## Definition of Done
- 成功执行用户指定的重置命令
- 验证重置后的工作区状态
- 确认所有指定的更改已被重置
- 报告重置操作的结果

## Verification
- 运行用户指定的重置命令
- 运行: git status 验证重置结果
- 运行: git log --oneline -3 验证提交历史
- 报告重置操作是否成功完成
@@@

请确认这个计划是否合适。
        """.trimIndent()

        val tasks = TaskParser.parse(routaOutput, workspace)

        assertEquals(3, tasks.size)
        assertEquals("任务 1: 检查当前代码状态", tasks[0].title)
        assertEquals("任务 2: 分析重置选项并获取用户确认", tasks[1].title)
        assertEquals("任务 3: 执行代码重置", tasks[2].title)

        // Verify sections are extracted
        assertTrue(tasks[0].objective.contains("Git状态"))
        assertEquals(4, tasks[0].scope.size)
        assertEquals(5, tasks[0].acceptanceCriteria.size)
        assertEquals(4, tasks[0].verificationCommands.size)
    }
}
