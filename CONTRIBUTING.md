# 参与贡献 lan-projects

感谢你考虑为 lan-projects 做出贡献！我们欢迎各类贡献，包括 Bug 修复、功能添加、文档改进以及翻译更新。

## 开始上手

1. Fork 本仓库
2. 克隆你的 Fork：
   ```bash
   git clone https://github.com/jidanbings/lan-projects.git
   cd lan-projects
   ```
3. 进入源代码目录并安装依赖：
   ```bash
   cd src
   npm install
   ```
4. 启动开发服务器：
   ```bash
   npm run start:dev
   ```
5. 在浏览器中打开 http://localhost:3000

## 开发流程

- 本项目遵循 [GitHub Flow](https://guides.github.com/introduction/flow/) 分支模型
- 从 `main` 分支创建特性分支：`git checkout -b feat/你的功能名`
- 完成修改并测试
- 提交清晰、描述性的 Commit 信息
- 推送并创建 Pull Request

## 代码风格

- JavaScript 使用 ES 模块语法（`import`/`export`）
- 前端代码遵循 `web/public/scripts/` 中已有的模式
- 后端代码遵循 `web/server/` 中的模式

## Pull Request 规范

- 保持 PR 专注于单一功能点
- 如有必要请更新文档
- 提交前在本地测试你的修改
- 在 PR 描述中关联相关 Issue

## 报告问题

- 使用 [GitHub Issue 跟踪器](https://github.com/jidanbings/lan-projects/issues)
- 提交前请先检查是否已有相同 Issue
- 包含复现步骤、预期行为和实际行为
- 包含浏览器和操作系统信息

## 翻译贡献

翻译文件位于 `web/public/lang/` 目录。添加或更新翻译：

1. 复制 `web/public/lang/en.json` 作为起始模板
2. 翻译其中的值（不要修改键名）
3. 以相应的 [ISO 639-1 代码](https://en.wikipedia.org/wiki/List_of_ISO_639-1_codes) 命名文件
4. 在 `web/public/index.html` 的语言选择器对话框中添加对应的语言按钮

## 许可证

提交贡献即表示你同意你的贡献将在 MIT 许可证下授权。
