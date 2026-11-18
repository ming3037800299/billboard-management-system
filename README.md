# 公路广告牌租赁定位系统（初版 v1.0）

面向公路沿线大型广告牌的**租赁信息展示 + 地图定位 + 后台管理 + 知识库问答**系统。对外公开展示广告牌实拍图、租赁报价、路段定位与完整详情；对内提供密码验证的后台管理端，支持广告牌数据全量增删改查，所有修改自动同步至前台展示；每条广告牌信息还会自动构建为知识库文档，支持自然语言语义检索与 RAG 问答。

```
前台展示（公开访问）        后台管理（密码进入）          数据库（MySQL 8）
┌──────────────────┐      ┌──────────────────┐      ┌──────────────────┐
│ 卡片列表 + 筛选    │      │ 密码验证登录      │      │  jqgg_glm53 库   │
│ 腾讯地图点位       │ ←──→ │ 增 / 删 / 改 / 查 │ ←──→ │  billboard 表    │
│ 详情弹窗 / 报价    │ 15秒 │ 多图上传/地图选点 │ 实时 │  软删除字段      │
└──────────────────┘ 同步  └──────────────────┘ 落库 └──────────────────┘
```

> 知识库：每条广告牌信息自动构建为结构化知识文本 → 本地 bge-m3 生成 1024 维向量 →
> 存入 `billboard_knowledge`；问答时先做“语义向量 + 结构化条件（价格 / 状态 / 位置）”混合检索，
> 再由云端大模型基于命中资料生成回答。纯语义结果低于相关度阈值（默认 0.5）会被过滤。
> 未启动本地向量服务或未配置问答模型 Key 时，系统仍可正常使用，页面会给出明确提示。

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Java 25 · Spring Boot 4.1.1 · Spring Data JPA（Hibernate 7） |
| 数据库 | MySQL 8.0（utf8mb4），独立数据库 `jqgg_glm53` |
| 前端 | **Vue 3**（Composition API + `<script setup>`）· Vue Router 4 · axios · Vite 7 · marked + DOMPurify（Markdown 渲染与清洗） |
| 地图 | 腾讯位置服务 JavaScript API GL（坐标系 GCJ-02） |
| 知识库 RAG | 本地 **bge-m3**（ONNX Runtime，1024 维；也兼容 Ollama）· 语义向量 + 结构化条件混合检索 · DeepSeek / 通义 OpenAI 兼容接口 |

> 前端拆分为两套独立 Vue 3 工程：`frontend-pc/`（电脑端，服务根路径 `/`）
> 与 `frontend-mobile/`（手机端，服务 `/m/`）。后端根据 User-Agent /
> `Sec-CH-UA-Mobile` 自动把手机重定向到手机端页面，两套前端共用同一套
> `/api` 业务接口和 MySQL 数据库。构建产物统一输出到 `src/main/resources/static/`，
> 因此**生产环境只需启动后端一个进程**即可同时提供两套页面与接口（无需 Nginx）。
>
> 需要部署到阿里云 ECS 等 Linux 服务器时，直接使用 [`deploy/`](deploy/README.md)：
> Docker Compose 会一并启动应用、MySQL 8 和本地 bge-m3 向量服务，并处理数据持久化、
> 健康检查、备份与更新。开发机执行 `bash deploy/build-local.sh` 生成部署包即可。
>
> 界面采用企业级 OA 布局：左侧按业务分类的 dock 导航 + 顶部面包屑 + 页面工作区；
> 筛选、统计、列表、地图等当前页内容统一留在页面内。

## 环境要求

- JDK 17+（本项目基于 JDK 25 开发验证）
- Maven 3.6+
- MySQL 8.0+（本机 3306 端口，服务已启动）
- Node.js 18+ 与 npm（仅前端开发 / 重新构建时需要；已构建好的产物已随项目提供）
- Python 3.10+（仅知识库的本地 bge-m3 ONNX 服务需要；也可改用 Ollama）
- 可选的云端问答模型 API Key（DeepSeek / 阿里百炼；未配置时智能选牌只返回检索结果）
- 访问前台地图需浏览器可联网（腾讯地图 JS API 在线加载）

## 快速启动（4 步）

### 第 1 步：初始化数据库

以 root 身份执行 `sql/init.sql`（脚本幂等，可重复执行；自动建库建表、创建最小权限应用账号、写入 29 条示例数据，含新疆昌吉周边 7 条，每条公路 2–4 块广告牌）：

```bash
mysql -u root -p --default-character-set=utf8mb4 < sql/init.sql
```

> 脚本创建应用专用账号 `jqgg_glm53_user`（本机无密码策略，密码为空），授予 `jqgg_glm53` 库的 SELECT/INSERT/UPDATE/DELETE 与 **ALTER** 权限（新增广告牌扩展字段需要 ALTER，无其他库权限）。本系统使用**独立数据库**，不影响同机其他项目。
> 如需为账号设置密码，修改 init.sql 中 `IDENTIFIED BY ''` 后通过环境变量 `DB_PASSWORD` 覆盖（见"配置说明"）。
> 从旧版本升级时重新执行一次本脚本即可补齐 `api_config`、`sys_maintenance_module`、`billboard_field` 等新表（`CREATE TABLE IF NOT EXISTS`，不会清空已有数据）；也可以单独执行 `sql/db-maintenance.sql` 只补齐数据库维护表与授权。

### 第 2 步：启动本地向量服务（知识库需要）

项目内置 bge-m3 的 ONNX 运行服务，无需安装 Ollama：

```bash
cd rag-service
pip install -r requirements.txt -i https://pypi.tuna.tsinghua.edu.cn/simple
# 首次运行下载约 2.3GB 模型，支持断点续传
download-model.bat
# 保持该窗口运行
start-embedding.bat
```

看到 `[bge-m3] 服务启动: http://127.0.0.1:11434` 即成功。
如果本机已安装 Ollama，也可以改用 `ollama pull bge-m3` + `ollama serve`，二者选其一（不能同时占用 11434 端口）。

> 未启动向量服务时，广告牌增删改查、前台展示、地图仍可正常使用；知识库索引会标记为失败，页面会提示。

### 第 3 步：启动应用

如需自然语言回答，先配置云端问答模型 Key。推荐写入项目根目录的本地私有配置
`config/application.yml`（该目录已被 `.gitignore` 忽略，不会进入代码仓库）：

```yaml
app:
  rag:
    llm:
      api-key: sk-...
```

也可以按原方式用环境变量覆盖（以 DeepSeek 为例，PowerShell）：

```powershell
$env:LLM_API_KEY="sk-..."; mvn spring-boot:run
```

阿里百炼（通义）：

```powershell
$env:LLM_BASE_URL="https://dashscope.aliyuncs.com/compatible-mode/v1"; $env:LLM_MODEL="qwen-plus"; $env:LLM_API_KEY="sk-..."; mvn spring-boot:run
```

> Spring Boot 会自动加载项目根目录下的 `config/application.yml`，其优先级高于
> `src/main/resources/application.yml`；修改后重启应用生效。
> 未配置问答模型 Key 时，智能选牌只返回知识库检索结果，不生成自然语言答案。
> 只做检索、不配置问答模型时，直接在项目根目录执行 `mvn spring-boot:run` 即可。

看到 `Started JqggApplication` 即启动成功。

> PC 端也可以直接使用项目根目录的启动脚本：Windows 双击 `start-pc.bat`，或在 Git Bash 执行 `bash start-pc.sh`。
> 脚本会自动设置跨库维护账号；移动端 `/m/` 保持不变。

### 第 4 步：访问测试

| 页面 | 地址 | 说明 |
|---|---|---|
| 前台展示页 | http://localhost:8080/ | 公开展示，无需登录；左侧 dock 进入"智能服务 → 智能选牌" |
| 后台管理页 | http://localhost:8080/admin | 点前台左侧 dock 的"系统管理 → 管理入口"输密码进入，或直接访问 |
| 后台知识库 | http://localhost:8080/admin | 登录后左侧 dock 进入"智能服务 → 知识库"，查看索引状态 / 重建索引 |

**本地默认管理密码：`admin123`**（生产部署由 `deploy/.env` 的 `ADMIN_PASSWORD` 覆盖，不要使用默认值上线）

### 建议测试流程

1. **前台浏览**：打开首页默认进入"广告牌资源"视图，查看卡片列表与数据概览；用关键词 / 路段 / 状态 / 价格排序筛选（两个视图共用同一套筛选条件）；点左侧 dock 的"广告牌业务 → 地图定位"切到整幅地图，点击彩色点位（绿=可租赁，红=已租赁，橙=暂停出租）查看信息窗；点卡片上的"地图定位"按钮会自动切到地图视图并居中到该广告牌。在"所在公路"中选择某条公路后，地图会展示该公路全部广告牌并高亮整段公路走向。点卡片或地图信息窗可查看详情（多图切换、完整参数）。
2. **后台管理**：点左侧 dock 的"管理入口"输入密码 `admin123`（只需输这一次，之后前后台来回切换都免密）→ 新增一条广告牌（可上传图片、展开地图选点自动填坐标）→ 保存 → **回到前台页面等待最多 15 秒**（无需刷新），新数据自动出现。
3. **编辑 / 删除**：在后台列表点选记录修改报价、状态、图片后保存，前台 15 秒内同步；删除后前台自动隐藏，勾选"含已删除"可查看并**恢复**。
4. **退出登录**：后台右上角"退出登录"，令牌即时作废。
5. **智能选牌**：前台左侧 dock 进入"智能服务 → 智能选牌"，依次试这些问题：
   - “杭州附近适合汽车品牌投放的广告牌”（语义 + 位置过滤）
   - “京沪高速有哪些广告牌点位”（公路关键词过滤）
   - “月租 3 万以内的可租赁广告牌”（价格 + 状态结构化筛选）
   - “最便宜的广告牌是哪个”（按价格排序）
   - “明天杭州天气怎么样”（应提示没有相关资料，且不返回广告牌来源）

   在输入区可切换模型（读取后台「API 管理」线路）、上传 PNG/JPEG 参考图做视觉相似度比对；回答在上方流式输出；发送后输入框自动清空，可继续追问，点“新对话”开始新会话。后台"智能服务 → 知识库"可查看 29 条广告牌的索引状态、向量维度和最近索引时间，并可一键重建。

### 前端二次开发（可选）

修改 Vue 源码后需要重新构建对应端的前端：

```bash
# 电脑端
cd frontend-pc
npm install
npm run dev          # http://localhost:5173，代理到本机 8080

# 手机端（独立工程，Vite base=/m/）
cd ../frontend-mobile
npm install
npm run dev          # http://localhost:5174/m/，代理到本机 8080
npm run dev:mobile   # 监听局域网，手机访问 http://电脑IP:5174/m/ 真机预览

# 构建两套前端并重新打包后端
cd ..
bash deploy/build-local.sh
```

> 两套前端共用一个后端：电脑端 `/`，手机端 `/m/`，后台分别为 `/admin` 与
> `/m/admin`。联调说明见 [`frontend-pc/README.md`](frontend-pc/README.md) 和
> [`frontend-mobile/README.md`](frontend-mobile/README.md)。
> 日常只改数据、不改页面的情况下，只需启动后端即可，无需 Node 环境。

## 功能清单

### 前台（公开，无需登录）

- 左侧 dock 按业务分类：广告牌业务（广告牌资源 / 地图定位）、系统管理（管理入口）；筛选、统计、列表、地图等当前页内容保留在页面内
- 广告牌卡片列表：实拍图、名称、路段/桩号、租赁报价、状态徽章、尺寸/材质/可视面积
- 筛选查询：关键词（名称/编号/路段/位置）、路段下拉、租赁状态、价格排序（低→高 / 高→低）
- 数据概览：总数 / 可租赁 / 已租赁 / 覆盖路段
- 地图定位：整幅地图展示全部广告牌点位标记（按状态着色），点击点位弹出信息窗，可跳转完整详情；卡片上的"地图"按钮会切到该视图并居中到对应广告牌
- 公路分布高亮：在"所在公路"筛选某条公路后，地图展示该公路**全部**广告牌（不受状态/价格等其它筛选影响），并按点位走向绘制蓝色高亮路段、标注公路名称和数量；清除公路筛选后恢复普通全景
- 详情弹窗：多图画廊（缩略图 + 左右方向键切换）、全部参数、报价说明、简介
- 自动同步：每 15 秒轮询后台数据变化（页面可见时），无需手动刷新；轮询不会打断前台操作——地图上手动缩放/平移的视野与打开的信息窗都会保留，只有用户主动改筛选条件时地图才重新全览
- 智能选牌（知识库 RAG 问答，助手“小金桥”）：**对话式布局**，系统回答在上方滚动区输出，输入框、图片上传和模型选择固定在下方；基于 bge-m3 语义向量 + 价格 / 状态 / 位置结构化条件的混合检索，支持“3 万以内”“最便宜”“已租赁”“京沪高速”等条件组合，纯语义结果低于阈值会被过滤；配置模型后通过 **SSE 流式逐字输出**，边接收边按 Markdown 渲染标题、列表和表格（DOMPurify 清洗）；**支持多轮连续追问**，自动携带最近对话历史，发送后立即清空输入框，可点“新对话”重置会话。参考广告牌在回答完成后显示在对话右侧独立栏（窄屏回退到回答下方），发送后输入框清空但保持焦点，便于连续追问。先判断用户意图：非选牌 / 闲聊问题进入普通聊天、不返回参考广告牌；流式生成默认自动跟随最新输出，用户主动上滚后暂停跟随、回到底部自动恢复，回答保持简练。 小金桥是高级公路广告牌营销推荐员，语气专业、热情、简洁，不会虚构资料。
- 图片选牌：支持上传 PNG / JPEG 参考图（≤10MB）。系统会把图片发送给当前选择的视觉模型识别文字（OCR）、画面、物体和可见品牌线索，同时后端用 Batik 把广告牌示例 SVG 光栅化，按颜色直方图、aHash/dHash 感知哈希、构图比例做本地视觉比对；两者结合生成相似性分析与选牌建议。图片只在内存中处理、不落盘，但会以 data URL 发送给云端视觉模型；模型不支持图片时自动降级为本地视觉特征分析。
- 模型切换：输入区模型下拉读取后台「API 管理」中启用的检索生成线路（没有数据库配置时回退 yml 模型列表），切换后该次提问和图片分析使用对应模型
- 管理入口：左侧 dock 的"系统管理 → 管理入口"打开密码验证弹窗，验证通过自动跳转后台；密码只需验证一次，之后前后台来回切换、刷新页面都免密，退出登录后（或令牌超时 / 后端重启导致令牌失效）才会重新要求

### 后台（固定密码进入）

- 密码登录 / 退出，令牌 2 小时滑动有效期（存于 localStorage，重启后端需重新登录）
- 左侧 dock 按业务分类：资源管理（广告牌管理）/ 系统管理（前台展示）
- 新增广告牌：全字段录入 + 多图上传（单张 ≤10MB）+ 地图选点自动填经纬度
- 压缩包批量上传：支持 `.zip`（≤100MB），自动解压并校验真实文件类型、单张大小（≤10MB）与数量（≤200 张）；非图片文件直接过滤，多级目录不影响导入；解压总量、条目数与 Zip Slip 均有防护
- 图片自动分类：按文件名关键词（宣传 / 实景 / 图纸）与图片基础特征（宽高比、白底留白、JPEG 拍摄信息）自动归入**宣传图 / 实景照片 / 设计图纸**，后台按分类分组展示缩略图，可指定封面、单张删除、手动调整分类，结果随广告牌保存并在前台详情中展示分类与封面
- 编辑广告牌：修改任意字段；旧图可勾选保留或移除，支持追加新图
- 删除广告牌：软删除（前台立即隐藏，数据保留可恢复）
- 查询筛选：关键词 / 状态 / 含已删除开关 / 手动刷新
- 恢复误删：一键恢复软删除的广告牌
- 知识库管理：查看每条广告牌的索引状态（已索引 / 待索引 / 失败）、向量模型、维度、最近索引时间；一键重建全部索引
- 索引自动同步：新增 / 编辑 / 删除 / 恢复广告牌后，后台异步更新对应知识文档；启动时自动补齐缺失索引
- API 管理（系统管理 → API 管理）：统一维护**向量模型、检索生成模型、地图服务、其他扩展接口**；支持新增 / 编辑 / 启停 / 设为默认 / 连通性测试，同一类型只有一条默认配置生效
- Key 安全：列表接口只返回脱敏 Key（如 `sk-****abcd`），编辑时留空表示保持原值，可勾选清空；Key 原文只在后端调用外部接口时使用
- 配置优先级：数据库中的启用配置优先于 `application.yml`；某类型没有启用配置时自动回退 yml；首次启动且 `api_config` 表为空时，会把 yml 中的向量 / 问答 / 地图线路写入数据库，方便可视化维护
- 热切换：问答模型每条配置代表一个可切换模型（前台模型下拉）；地图 Key 修改后前台刷新即生效；向量模型地址 / 模型修改后自动异步重建知识库索引
- 数据库维护（系统管理 → 数据库维护）：按表名 / 字段名 / 中文注释 / 表内数据内容检索数据所在表与字段；可配置多个子模块，支持为广告牌新增扩展字段 / 属性并**同步执行 ALTER TABLE**，新增字段自动出现在广告牌表单与前台详情中
- 字段管理与表单设计（系统管理 → 数据库维护）：内置“字段管理”和“表单设计”子模块；表单设计器支持字段库、拖拽排版、编辑 / 预览双模式，逐字段设置必填、控件类型、下拉选项、栏宽和前台展示，后台广告牌表单按同一套配置渲染，所有配置写入数据库
- 预定与合同（租赁业务 → 预定与合同）：在广告牌列表点「预定」自动带入选中广告牌的编号 / 名称 / 路段 / 尺寸 / 材质 / 单价，填写乙方与租期后广告牌锁定为「已预定」；按 Word 模板生成合同，复制签署链接或二维码给乙方；支持在线手写签名、上传签名图片、上传线下回签件、作废 / 结束合同
- 签约设置（系统管理 → 签约设置）：维护甲方签约主体与电子签章（首次提供示例主体和示例章，正式使用请替换）；上传 / 启停 / 设为默认 `.docx` 合同模板，模板需包含 `{{@甲方签章}}` 与 `{{@乙方签名}}` 图片占位符

### 压缩包批量上传与图片自动分类

后台新增 / 编辑广告牌时，图片区域提供两种入口：上传 `.zip` 压缩包批量导入，或直接多选图片（两者走同一套校验与分类逻辑）。

- **压缩包规则**：≤100MB、最多 1000 个条目、最多导入 200 张图片、单张 ≤10MB、解压总量 ≤300MB；按文件头识别真实格式（jpg/png/gif/bmp/webp），txt/docx/pdf 等非图片自动过滤并返回原因；忽略 `__MACOSX`、隐藏文件、`Thumbs.db`；拒绝绝对路径与 `..`（防 Zip Slip），多级目录扁平化导入；Windows 中文压缩包出现乱码时自动按 GBK 重新解码。
- **自动分类**：先按文件名关键词（宣传/海报/主视觉、实景/现场/实拍、设计/图纸/效果图等）加权，再结合宽高比（横幅偏宣传图）、大面积白底留白（偏设计图纸）、JPEG EXIF 拍摄设备/时间（偏实景照片）综合打分，返回分类、置信度与识别依据。命名不规范、缺少拍摄信息的图片置信度会偏低，可在预览中人工调整。
- **保存与展示**：导入的图片先落盘到 `uploads/imports/{会话}/` 临时目录，保存广告牌时转存到常规 `uploads/yyyyMM/` 并把封面排到 `images` 首位；分类、封面、原始文件名写入 `image_meta`。前台详情画廊显示当前图片分类与“封面”标记，卡片列表使用封面图。
- **接口**：`POST /api/admin/billboards/images/import`（zip）、`POST /api/admin/billboards/images/classify`（多图）、`DELETE /api/admin/billboards/images/import/{sessionId}`（清理未保存的临时目录）。

### 知识库检索策略（增强检索）

问答请求会先由 `KnowledgeQueryAnalyzer` 解析查询意图，再按两类路径检索：

1. **语义检索**：bge-m3 计算问题与全部知识文档的余弦相似度，按 `min-score`（默认 0.5）过滤低相关结果，
   返回 `top-k`（默认 5）条；问题中出现公路 / 地名 / 设施词时，会先做词面过滤，避免语义相近但位置不符。
2. **结构化筛选**：问题包含价格、状态、排序等条件时，从全部广告牌中筛选，而不是只看向量 topK：
   - 价格区间：支持“3 万以内”“30000 以下”“2 万以上”“年租 30 万以内”等，年付 / 季付 / 日付统一折算为月均价比较；
   - 租赁状态：可租赁 / 已租赁 / 暂停出租；
   - 位置 / 设施：杭州、京沪高速、高炮、单立柱、跨线桥等出现在广告牌名称 / 公路 / 路段 / 地址中的关键词；
   - 价格排序：“最便宜 / 最贵”按折算后的月均价排序，并取最优的 10 条送入模型。
3. **流式生成**：结构化筛选最多把 20 条符合项（价格排序取前 10 条）连同“月均折算”一起送入大模型，
   提示词明确要求逐条核对，避免只依据相似度前几条下结论。前端调用 `/api/knowledge/ask/stream`，
   服务端按 `meta`（参考广告牌 / 模型信息）→ `delta`（增量文本）→ `done`（完整结果）推送 SSE；
   前端用 `fetch` + `ReadableStream` 边接收边渲染，生成期间显示“生成中…”和光标。未配置问答模型时，
   检索摘要也会作为流式内容返回。
4. **格式与编码**：前端使用 `marked` 解析模型返回的 Markdown，经 `DOMPurify` 清洗后渲染标题、列表和表格，
   不再把 `**加粗**` 或表格分隔符当纯文本显示；后端 JSON 响应显式声明 `charset=UTF-8`，SSE 也按 UTF-8 解码。

5. **图片选牌**：`POST /api/knowledge/ask/image/stream` 接收 PNG/JPEG（≤10MB），用 Batik 把广告牌示例 SVG
   光栅化后，与上传图片做颜色直方图、aHash/dHash 感知哈希和构图比例比对；返回视觉相似度前 5 名，并把
   主色 / 亮度 / 色彩 / 构图摘要交给当前选择的大模型生成相似性分析与选牌建议。图片仅在内存中处理，不落盘。

6. **宽泛推荐**：用户只说“推荐几个”“推荐几个有性价比的牌子”等，没有预算 / 路段 / 品牌条件时，
   不直接返回“没有找到”，而是从可租赁广告牌中挑选代表性候选（优先月均价格低、覆盖不同公路），
   让小金桥给出 3 个左右的初选建议并追问预算、路段和品牌调性。

示例（当前 29 条示例数据）：

| 问题 | 检索结果 |
|---|---|
| 月租 3 万以内的可租赁广告牌 | 12 条（含 2 条年付报价的月均折算） |
| 最便宜的广告牌是哪个 | 16000 元/月（暂停出租）；当前可租最低为 17000 元/月 |
| 京沪高速有哪些广告牌点位 | 4 条 |
| 明天杭州天气怎么样 | 无相关资料，不返回广告牌来源 |

## 预定与合同模块（预览版）

后台「租赁业务 → 预定与合同」提供完整闭环：

1. 在「广告牌管理」列表中点击某块可租赁广告牌的「预定」，自动带入广告牌快照；
2. 填写乙方信息、投放起止日期、租金、定金、付款方式与广告内容，保存后广告牌变为「已预定」，其他客户不能再预定；
3. 在预定详情中按默认模板或指定模板生成 `.docx` 合同，系统自动写入广告牌信息、甲方预设信息与签章；
4. 复制签署链接或二维码发给乙方；乙方打开 `/sign/{token}` 免登录查看合同，手写签名或上传签名图片后，系统把签名嵌入合同并生成最终 Word；
5. 也支持管理员或乙方直接上传线下已签合同（PDF / JPG / PNG / DOCX）归档；
6. 未签合同可取消并释放广告牌；已签合同可作废或提前结束；合同结束日期过后由每日定时任务自动完成并释放广告牌。

**并发与一致性**：合同号 / 预定号由 `sys_sequence` 表原子分配；生成合同前锁定预定行，避免同一预定生成多份有效合同；在线签署使用“行锁 + 状态条件更新”（`UPDATE ... WHERE status='PENDING_SIGN'`），并发双签只有一个成功；作废 / 结束 / 到期任务按“广告牌 → 预定 → 合同”统一顺序加锁，到期任务重复调度会跳过已处理合同；合同文件在事务回滚时自动清理。预定创建 / 修改会校验同一广告牌的投放日期重叠。

**模板占位符**：文本字段使用 `{{字段}}`，图片使用 `{{@甲方签章}}`、`{{@乙方签名}}`；后台「签约设置」会显示模板实际识别到的占位符，并要求两个图片占位符必须存在。默认内置模板的字段说明见「签约设置 → 查看支持的合同字段」。

**文件安全**：合同、模板、甲方签章、乙方签名保存在私有目录（默认 `uploads-private/`，可用 `CONTRACT_DIR` 覆盖），不映射为 `/uploads/**` 静态资源；只能通过管理员令牌或签署令牌接口读取。签署链接默认 7 天有效（可在签约设置中调整），可随时刷新使旧链接失效；签署记录包含姓名、时间与来源 IP。

**数据库升级**：已有数据库执行 `sql/contract-module.sql` 即可补齐 `contract_settings`、`contract_template`、`reservation`、`contract`、`contract_file`、`contract_event` 六张表；执行 `sql/billboard-image-meta.sql` 为 `billboard` 表补齐图片分类元信息列 `image_meta`；执行 `sql/concurrency-fixes.sql` 补齐合同号/预定号原子分配表 `sys_sequence` 与并发查询索引（三个脚本均幂等，可重复执行）。全新数据库执行 `sql/init.sql` 会自动包含这些表和列。前端需重新构建 PC 端（`frontend-pc`）并复制到 `src/main/resources/static/`。

**设计方案**：合同要素、签署流程、模板占位符、数据模型、状态流转与异常场景见 [`docs/合同签订功能设计方案.md`](docs/合同签订功能设计方案.md)。

**接口概览**：后台 `/api/admin/contract-settings`、`/api/admin/contract-templates/**`、`/api/admin/reservations/**`、`/api/admin/contracts/**`；乙方公开接口 `/api/sign/{token}`、`/api/sign/{token}/file`、`/api/sign/{token}/sign`、`/api/sign/{token}/upload`。

> 预览版默认写入一条带「示例」字样的甲方主体与示例签章，并提供 3 条演示数据（2 条已签约、1 条待签署）。正式使用前请在「签约设置」中替换甲方信息、签章和合同模板，并按实际业务复核合同条款。

## 数据库维护模块

后台左侧「系统管理 → 数据库维护」提供数据库列表、结构检索、跨库字段检索与任意表字段维护，所有接口都在 `/api/admin/db/**` 下，沿用管理员令牌鉴权。

### 数据库列表与切换

页面顶部新增数据库选择栏：

- 列出维护账号可见的非系统数据库，显示每个库的表数 / 字段数；
- 选择某个数据库后，“数据库检索”和“数据表结构”只针对该库加载；
- 未配置独立维护账号时，只显示应用主账号有权限的库（通常只有 `jqgg_glm53`）；
- 配置具备跨库权限的维护账号后，可看到服务器上的其他业务库，并进入对应库管理字段。配置方式见下方“数据库权限与维护账号”。

### 1. 子模块

页面左侧是维护子模块列表，内置 6 个，也可以继续新建（例如“照明扩展字段”“运营属性”等）：

| 子模块类型 | 作用 |
|---|---|
| 数据库检索 | 输入表名、字段名、中文注释或数据内容，定位数据所在的库、表与字段 |
| 数据表结构 | 浏览选中数据库的全部表、字段类型、注释、主键与数据量，并可在任意表新增字段 |
| 广告牌字段 | 固定维护项目库 `billboard` 表的扩展字段；新增时同步 `ALTER TABLE billboard ADD COLUMN` |
| 广告牌属性 | 与广告牌字段相同，适合用下拉、日期、开关等业务属性来组织 |
| 字段管理 | 集中启用 / 停用扩展字段，调整表单分区、栏宽和排序，也可新增 / 删除扩展字段 |
| 表单设计 | 按“基本信息 / 位置 / 价格 / 参数 / 扩展信息”五个分区编排扩展字段，实时预览表单效果 |

新建子模块时填写名称、唯一标识（小写英文/数字/下划线）和类型；类型创建后不可修改，避免已有字段失去归属。内置子模块不可删除，但可以改名、停用或调整排序。

### 2. 查询数据所在的表与字段

- 默认检索表名、表注释、字段名、字段注释与字段类型，例如输入“报价”会命中 `billboard.price_note`（报价说明）；
- “检索范围”可切换“当前数据库 / 全部数据库”：
  - 当前数据库：结果包含命中值、行标识，可勾选“同时检索表内数据内容”扫描真实数据（如 `id=1`）；
  - 全部数据库：跨库检索字段，结果列表直接显示字段所属数据库，适合先定位字段落在哪个库；
- 为避免拖慢数据库，数据检索最多扫描 30 张表、单表 25 个字段，命中上限 80 条，超大表会跳过并标记“结果已截断”；跨库检索不扫描表内数据。

### 3. 已有字段与新增字段 / 属性

#### 3.1 查看已有字段（直接关联数据库）

进入“广告牌字段”或“广告牌属性”子模块后，页面会从 `billboard` 表**实际结构**读取并列出全部已有字段，包括：

- 系统字段：`id`、`name`、`code`、`road`、`price`、`price_note` 等 21 个核心列；
- 扩展字段：通过本模块新增的字段；
- 每一行显示字段来源（系统字段 / 扩展字段所属子模块）、数据库列名、类型、数据库注释、可空性、启用状态、表单分区、栏宽、前台展示、同步状态。

其中：

- **系统字段**点击“编辑”可修改兼容范围内的类型、长度 / 精度、可空性和注释（详见 3.2）；物理列名、主键/时间戳/删除标记等由业务代码维护的属性保持只读，默认值、索引、`ON UPDATE` 等定义会保留；
- **扩展字段**可执行“编辑 / 同步 / 删除”，显示名称、选项、默认值、必填、前台展示等修改会同步到 `billboard_field` 元数据与数据库列注释；编辑字段名/类型见 3.2；删除仍执行 `DROP COLUMN`；
- 广告牌**行数据**（如某块广告牌的名称、价格、扩展字段值）在「广告牌管理」中编辑，保存后直接写入数据库并同步知识库；数据库维护页负责字段结构与注释维护。

#### 3.2 编辑字段（右侧抽屉）

点击字段行末的“编辑”，会从右侧打开字段编辑抽屉（不再使用页面底部的内联表单，避免长表格中点击后看不到反馈）：

- 可修改显示名称、字段名（数据库列名）、输入类型、长度 / 精度、小数位、默认值、下拉选项、必填、前台展示、排序、提示文字和**数据库可空性（NULL / NOT NULL）**；
- 抽屉会展示字段**数据概况**（总记录 / 非空 / 空值 / 不同值 / 示例值），改类型或改非空前可以先评估影响；
- 抽屉底部实时生成并支持复制**将要执行的 DDL**，保存前心里有数；
- 修改字段名、类型或可空性时，保存前会二次确认，并执行 `ALTER TABLE billboard CHANGE COLUMN ...`；已有数据由 MySQL 自动转换，可能丢失精度或格式，请先备份；
- 非空字段若未填写默认值，数据库会自动补一个类型空值默认值作为插入兜底（避免 JPA 插入主记录时 NOT NULL 报错）；应用层仍要求填写或使用默认值；
- 保存后字段元数据、数据库列注释、后台广告牌表单、前台详情会同步更新，已有字段值保留；
- 系统字段（`id`、`name`、`price` 等）点击“编辑”后：
  - `name`、`road`、`price`、`description` 等业务字段可以修改**兼容类型、长度 / 精度、可空性**和注释；
  - `code`、`road`、`status` 等带索引的字符串字段限制为 VARCHAR，避免破坏索引；
  - `id`、`deleted`、`created_at`、`updated_at` 的结构由业务代码维护，只读，只能修改注释；
  - 物理列名不可修改，避免破坏 JPA 实体映射；默认值、索引、`ON UPDATE` 等属性会保留；
- 字段列表顶部提供过滤框，可按显示名称、数据库列、注释或子模块快速定位；按 Esc 可关闭抽屉。

> 字段重命名 / 类型变更需要 MySQL 8 的在线结构变更能力；H2 等测试数据库会提示不支持在线重命名或改类型。

#### 3.3 新增广告牌字段 / 属性

在“广告牌字段”或“广告牌属性”子模块中点击“新增字段”：

1. 填写显示名称、字段名（真实数据库列名，如 `lighting_type`）、输入类型（单行文本 / 多行文本 / 整数 / 小数 / 日期 / 日期时间 / 下拉选项 / 是-否）、长度、默认值、是否必填、是否前台展示等；
2. 保存时系统先登记字段元数据，再执行 `ALTER TABLE billboard ADD COLUMN ...`，并写入字段中文注释；
3. 新增成功后：
   - 字段会立即出现在“已有字段”列表和后台广告牌表单中，可随广告牌一起保存；
   - 勾选“在前台广告牌详情中展示”时，前台详情会展示该字段值；
   - 字段值会一并写入知识库文本，参与智能选牌的语义检索；
4. 字段列表会显示“已同步 / 待同步”。如果数据库账号缺少 ALTER 权限或执行失败，字段会保留为“待同步”，授权后可点击“同步”重试；
5. 删除字段会执行 `DROP COLUMN`，**该列已有数据会永久丢失**，页面会二次确认。

> 必填是应用层校验：数据库列统一允许 NULL，避免存量广告牌导致加列失败。多行文本（TEXT）按 MySQL 限制不支持数据库默认值。

#### 3.4 字段管理与表单设计

内置的“字段管理”和“表单设计”子模块用于维护**扩展字段的展示方式和控件定义**。布局修改不改变物理表结构；修改控件类型 / 长度时才会按需同步数据库列：

- **字段管理**：集中列出 `billboard` 的系统字段和全部扩展字段。系统字段只读；扩展字段可勾选启用 / 停用、选择表单分区、选择半行 / 整行栏宽、上下移动排序，也可进入编辑抽屉或直接删除；
- **表单设计（编辑模式）**：
  1. 左侧“字段库”列出尚未放入表单的扩展字段，选择目标分区后点击“放入”；系统字段作为固定字段展示，不能移除；
  2. 中间画布按五个分区展示字段，拖动字段左侧的 `⋮⋮` 手柄可跨分区拖拽或调整顺序；也可以使用分区下拉框、↑ / ↓ 按钮；
  3. 每个字段可单独设置**必填**、**前台展示**、**控件类型**（单行文本 / 多行文本 / 整数 / 小数 / 日期 / 日期时间 / 下拉选项 / 是-否）和**栏宽**；
  4. 控件类型选择“下拉选项”时，打开选项编辑器，可添加、删除、上移、下移选项；选项会实时出现在右侧预览中；
  5. “从表单移除”只把字段 `enabled` 置为 0，不删除数据库列；需要彻底删除时到“字段管理”执行删除；
  6. 点击“保存设计”统一提交。布局 / 必填 / 分区 / 栏宽 / 顺序 / 前台展示写入 `billboard_field`；修改控件类型、长度、默认值、选项等会同步字段元数据，类型变化会二次确认并执行 `ALTER TABLE`；
- **表单设计（预览模式）**：隐藏编辑区，按最终分区、栏宽、必填标记和下拉选项渲染只读表单，点击“进入编辑设计”可继续修改；
- **设计器内新增字段**：点击“+ 新增字段”可直接创建扩展字段（显示名称、列名、控件类型、分区、栏宽、必填、前台展示、下拉选项），保存后同步执行 `ALTER TABLE billboard ADD COLUMN` 并放入表单；
- 后台“广告牌管理”的新增 / 编辑表单按相同的分区、栏宽和排序渲染扩展字段，保存时写入真实数据库列；前台详情只展示 `enabled=1` 且 `visible=1` 的字段；
- 所有布局配置通过 `PUT /api/admin/db/fields/{id}/layout` 写入 `billboard_field` 的 `enabled / section / width / sort_order / required / visible` 列，刷新页面或重启服务后保持。

> 表单设计器同时驱动只读预览和后台数据录入表单：预览是最终排版，后台广告牌表单是同一排版的可编辑版本。前台详情页是展示页而非公开填写入口，按字段的启用状态和“前台展示”开关渲染。

#### 3.5 在任意表新增字段（跨库维护）

在“数据表结构”子模块中：

1. 顶部选择一个数据库；
2. 展开任意数据表，点击“新增字段”；
3. 填写字段名、类型（VARCHAR / TEXT / INT / BIGINT / DECIMAL / DATE / DATETIME / TINYINT）、长度 / 精度、默认值、注释、是否允许 NULL；
4. 保存时执行 `ALTER TABLE <数据库>.<表> ADD COLUMN ...`，成功后立即刷新该库表结构。

说明：

- 项目库的 `billboard` 表会提示前往“广告牌字段 / 广告牌属性”子模块新增，以保证字段同时接入后台表单、前台展示和知识库；
- 非项目数据库的新增字段只修改表结构，不会同步到本项目的业务页面；页面会显示风险提示；
- 通用维护只开放“新增字段”，不提供任意表的删列 / 改类型操作，避免误操作破坏其他系统；
- 取消“允许 NULL”时必须填写默认值，避免存量数据加列失败；
- 需要维护账号对目标库具备 `ALTER` 权限（查询结构还需要 `SELECT`，列出数据库需要 `SHOW DATABASES`）。

### 4. 数据库权限与升级

- 全新安装：`sql/init.sql` 已包含 `sys_maintenance_module`、`billboard_field` 两张表，并给应用账号授予 `ALTER` 权限；
- 已有部署升级：使用 root 执行一次 `sql/db-maintenance.sql`（幂等），创建上述两张表并补充 `GRANT ALTER`；
- 应用账号需要 `ALTER` 权限才能新增 / 删除扩展字段列；数据库维护页顶部会显示“允许 ALTER / 缺少 ALTER”状态。缺少权限时，页面会给出可直接执行的 `GRANT` 语句。

#### 跨库维护账号（可选）

默认情况下，数据库维护模块复用应用主数据源，只能看到应用账号有权限的库。若需要列出并管理服务器上的其他数据库，执行：

```bash
mysql -u root -p --default-character-set=utf8mb4 < sql/db-maintenance-user.sql
```

脚本会创建 `jqgg_maint_user` 并授予 `SELECT, ALTER, SHOW DATABASES ON *.*`。随后启动应用时设置：

```bash
DB_MAINT_USERNAME=jqgg_maint_user DB_MAINT_PASSWORD=你的密码 java -jar deploy/app.jar
```

> 项目根目录的 `start-pc.bat` / `start-pc.sh` 已默认设置 `DB_MAINT_USERNAME=jqgg_maint_user`，
> 直接运行即可；如需自定义账号，先设置环境变量再运行脚本。

或通过环境变量 / 配置中心覆盖：

| 配置项 | 环境变量 | 说明 |
|---|---|---|
| `app.db-maintenance.username` | `DB_MAINT_USERNAME` | 维护账号；留空沿用主数据源 |
| `app.db-maintenance.password` | `DB_MAINT_PASSWORD` | 维护账号密码 |
| `app.db-maintenance.url` | `DB_MAINT_URL` | 维护连接地址；留空沿用 `spring.datasource.url` |
| `app.db-maintenance.enabled` | `DB_MAINT_ENABLED` | 是否启用独立维护连接，默认 `true` |

> 跨库维护账号可以读取所有库的数据并修改表结构，仅建议在受控的开发 / 运维环境使用；
> 生产环境建议按库授权，或仅开启本项目库的维护能力。

## 配置说明（application.yml，均支持环境变量覆盖）

| 配置项 | 环境变量 | 默认值 | 说明 |
|---|---|---|---|
| `app.admin.password` | `ADMIN_PASSWORD` | `admin123` | 后台管理密码 |
| `spring.datasource.username` | `DB_USERNAME` | `jqgg_glm53_user` | 数据库账号 |
| `spring.datasource.password` | `DB_PASSWORD` | （空） | 数据库密码 |
| `app.db-maintenance.enabled` | `DB_MAINT_ENABLED` | `true` | 是否启用独立维护连接；关闭后沿用主数据源 |
| `app.db-maintenance.url` | `DB_MAINT_URL` | （空） | 维护连接地址；留空沿用 `spring.datasource.url` |
| `app.db-maintenance.username` | `DB_MAINT_USERNAME` | （空） | 跨库维护账号；留空只能看到应用库 |
| `app.db-maintenance.password` | `DB_MAINT_PASSWORD` | （空） | 维护账号密码 |
| `app.map.ak` | `MAP_AK` | 已内置可用 Key | 腾讯地图 Key（[申请入口](https://lbs.qq.com/dev/console/application/mine)） |
| `app.rag.enabled` | `RAG_ENABLED` | `true` | 知识库开关 |
| `app.rag.top-k` | `RAG_TOP_K` | `5` | 纯语义检索默认返回条数（结构化筛选最多可送入 20 条） |
| `app.rag.min-score` | `RAG_MIN_SCORE` | `0.5` | 纯语义检索最低余弦相似度；低于该值的资料不参与回答，0 表示不过滤 |
| `app.rag.embedding.base-url` | `OLLAMA_BASE_URL` | `http://127.0.0.1:11434` | 本地向量服务地址（Python 服务或 Ollama） |
| `app.rag.embedding.model` | `EMBEDDING_MODEL` | `bge-m3` | 向量模型 |
| `app.rag.llm.base-url` | `LLM_BASE_URL` | `https://api.deepseek.com/v1` | 云端问答模型 OpenAI 兼容地址 |
| `app.rag.llm.model` | `LLM_MODEL` | `deepseek-chat` | 云端问答模型名称 |
| `app.rag.llm.api-key` | `LLM_API_KEY` | （空） | 云端问答模型 Key；为空时只返回检索结果 |
| `app.rag.llm.max-tokens` | `LLM_MAX_TOKENS` | `2000` | 单次回答最大 token 数；结构化结果较多或答案被截断时可调大 |
| `app.upload-dir` | `UPLOAD_DIR` | `uploads` | 上传图片目录；相对路径按应用根目录解析，也可写绝对路径 |
| `app.contract-dir` | `CONTRACT_DIR` | `uploads-private` | 合同 / 模板 / 签章 / 签名私有目录；相对路径按应用根目录解析，不对外静态映射 |
| `app.api.seed-defaults` | `API_SEED_DEFAULTS` | `true` | `api_config` 表为空时是否把上面的默认接口写入数据库 |
| `server.port` | `SERVER_PORT` | `8080` | 服务端口 |

> **目录无关启动**：`uploads/`、`uploads-private/` 和本地 `config/application.yml` 不再依赖启动时的工作目录。
> `start-pc.bat` / `start-pc.sh` 会通过 `-Dapp.home=项目根目录` 显式指定；直接 `java -jar` 启动时，
> 应用会根据 jar / classpath 位置自动推断项目根目录（`target`、`deploy` 下的 jar 均视为项目根目录下的构建产物）。
> 因此整个项目文件夹可以移动到任意位置；如需手工指定，可用 `java -Dapp.home=/path/to/jqgg_contract -jar ...` 或环境变量 `APP_HOME`。
>
> 上表中 `app.rag.embedding.*`（向量模型）、`app.rag.llm.*`（检索生成模型）和 `app.map.ak`（地图）
> 现在作为**默认 / 兜底配置**：首次启动会自动初始化到 `api_config` 表，之后以后台「API 管理」中的配置为准。
> 已有 `api_config` 数据时不会重复初始化；设置 `API_SEED_DEFAULTS=false` 可完全沿用 yml。
>
> 模型下拉优先读取后台「API 管理」中启用的检索生成线路；仅在没有数据库线路时使用 `app.rag.llm.models`（yml）作为兜底列表。

> 项目根目录的 `config/application.yml` 是本地私有配置（已加入 `.gitignore`），
> 适合存放 `LLM_API_KEY` 等敏感项；其优先级高于 `src/main/resources/application.yml`，
> 修改后重启应用生效。部署到其他机器时请单独配置，不要把密钥提交到仓库。

Windows PowerShell 设置环境变量示例：

```powershell
$env:ADMIN_PASSWORD="new-pass-456"; mvn spring-boot:run
```

云端问答模型（二者选其一）：

```powershell
# DeepSeek（默认地址与模型）
$env:LLM_API_KEY="sk-..."; mvn spring-boot:run

# 阿里百炼 / 通义
$env:LLM_BASE_URL="https://dashscope.aliyuncs.com/compatible-mode/v1"
$env:LLM_MODEL="qwen-plus"
$env:LLM_API_KEY="sk-..."
mvn spring-boot:run
```

## 数据库设计（核心表 billboard）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT 自增 | 主键，唯一标识 |
| name | VARCHAR(100) | 广告牌名称（必填） |
| code | VARCHAR(50) | 广告牌编号，**唯一索引** |
| road / section | VARCHAR | 所在公路 / 路段位置（桩号） |
| address | VARCHAR(500) | 详细地址 |
| longitude / latitude | DECIMAL(10,6) | 经纬度（GCJ-02，地图定位用） |
| images | TEXT | 图片路径 JSON 数组（支持多图） |
| image_meta | TEXT | 图片元信息 JSON 数组：`url` / `category`（PROMO 宣传图、REAL_PHOTO 实景照片、DESIGN_DRAWING 设计图纸）/ `cover` 是否封面 / `name` 原始文件名；封面始终排在 images 首位 |
| price | DECIMAL(12,2) | 租赁单价 |
| price_unit | VARCHAR(20) | 计费周期（元/月、元/年） |
| price_note | VARCHAR(500) | 报价说明 |
| size / material / visible_area / audience | VARCHAR | 尺寸 / 材质 / 可视面积 / 受众范围 |
| status | VARCHAR(20) | AVAILABLE 可租赁 / RENTED 已租赁 / PAUSED 暂停出租（索引） |
| description | TEXT | 详细简介 |
| deleted | TINYINT(1) | 软删除标记（0 正常 / 1 已删除） |
| created_at / updated_at | DATETIME | 创建 / 更新时间（应用层自动维护 + 数据库默认值兜底） |

### 知识库表 billboard_knowledge

| 字段 | 类型 | 说明 |
|---|---|---|
| billboard_id | BIGINT 主键 | 对应 `billboard.id`，一条广告牌一条知识文档 |
| content | MEDIUMTEXT | 由广告牌字段拼接的结构化知识文本 |
| content_hash | CHAR(64) | 内容 + 模型名称的 SHA-256，未变化时跳过重复向量化 |
| embedding | MEDIUMTEXT | 向量 JSON 数组（bge-m3 为 1024 维） |
| dimension | INT | 向量维度 |
| model | VARCHAR(100) | 向量模型名称 |
| status | VARCHAR(20) | PENDING / READY / ERROR |
| error_message | VARCHAR(500) | 最近一次索引错误 |
| indexed_at / updated_at | DATETIME | 最近成功索引 / 更新时间 |

### API 配置表 api_config

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT 自增 | 主键 |
| name | VARCHAR(100) | 配置名称，全局唯一 |
| type | VARCHAR(30) | EMBEDDING 向量模型 / LLM 检索生成 / MAP 地图 / OTHER 其他 |
| provider | VARCHAR(30) | OLLAMA / OPENAI / TENCENT / HTTP |
| base_url | VARCHAR(500) | 接口根地址 |
| api_key | VARCHAR(1000) | API Key / 地图 AK（列表接口脱敏，不返回原文） |
| model | VARCHAR(200) | 模型名称 |
| max_tokens / temperature | INT / DECIMAL(4,2) | 检索生成模型参数 |
| timeout_seconds | INT | 读取超时（秒） |
| enabled | TINYINT(1) | 是否启用 |
| is_default | TINYINT(1) | 是否同类默认配置（同一类型最多一条） |
| remark | VARCHAR(500) | 备注 |
| created_at / updated_at | DATETIME | 创建 / 更新时间 |

> `api_key` 以明文保存在数据库中（属于后台运维数据），请限制数据库访问权限；
> 管理接口只返回 `apiKeyMasked` 脱敏值。

### 数据库维护表

`sys_maintenance_module`（维护子模块）：

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT 自增 | 主键 |
| name / code | VARCHAR | 子模块名称 / 唯一标识 |
| type | VARCHAR(30) | SEARCH / SCHEMA / BILLBOARD_FIELD / BILLBOARD_ATTRIBUTE / FIELD_MANAGER / FORM_DESIGNER |
| description | VARCHAR(500) | 说明 |
| sort_order | INT | 排序号 |
| enabled / builtin | TINYINT(1) | 是否启用 / 是否内置（内置不可删除） |
| created_at / updated_at | DATETIME | 创建 / 更新时间 |

`billboard_field`（广告牌扩展字段定义，每行对应 `billboard` 表中的一个真实物理列）：

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT 自增 | 主键 |
| module_id | BIGINT | 所属子模块 |
| field_name | VARCHAR(64) | 物理列名，全局唯一 |
| label | VARCHAR(100) | 显示名称（同时写入数据库列注释） |
| input_type | VARCHAR(20) | TEXT / TEXTAREA / INTEGER / DECIMAL / DATE / DATETIME / SELECT / BOOLEAN |
| column_type | VARCHAR(50) | 物理列类型，如 VARCHAR(200)、DECIMAL(18,2) |
| field_length / decimals | INT | 文本长度 / 小数位 |
| required / visible | TINYINT(1) | 应用层必填 / 前台详情展示 |
| nullable | TINYINT(1) | 数据库是否允许 NULL（0 非空，1 可空） |
| enabled | TINYINT(1) | 是否启用：0 停用后保留列与数据，只从后台表单和公开字段接口隐藏 |
| section | VARCHAR(30) | 表单分区：basic / location / price / params / extension |
| width | VARCHAR(10) | 表单栏宽：half（半行）/ full（整行） |
| default_value / options_text | VARCHAR / TEXT | 默认值 / 下拉选项（换行分隔） |
| placeholder | VARCHAR(200) | 表单提示 |
| sort_order | INT | 排序号 |
| created_at / updated_at | DATETIME | 创建 / 更新时间 |

## API 一览

| 方法 | 路径 | 鉴权 | 说明 |
|---|---|---|---|
| GET | `/api/site-config` | 公开 | 站点配置（站名、地图 Key） |
| GET | `/api/billboards` | 公开 | 广告牌列表（road/status/minPrice/maxPrice/keyword/sort） |
| GET | `/api/billboards/roads` | 公开 | 去重路段列表 |
| GET | `/api/billboards/custom-fields` | 公开 | 已启用且允许前台展示的扩展字段元数据（enabled=1 且 visible=1，供前台详情使用） |
| GET | `/api/billboards/{id}` | 公开 | 广告牌详情（含 `extraValues` 扩展字段值） |
| POST | `/api/admin/login` | 公开 | 密码验证，返回令牌 |
| POST | `/api/admin/logout` | 令牌 | 退出登录 |
| GET | `/api/admin/billboards` | 令牌 | 管理列表（含 includeDeleted） |
| POST | `/api/admin/billboards` | 令牌 | 新增（multipart） |
| PUT | `/api/admin/billboards/{id}` | 令牌 | 编辑（multipart） |
| DELETE | `/api/admin/billboards/{id}` | 令牌 | 软删除 |
| POST | `/api/admin/billboards/{id}/restore` | 令牌 | 恢复软删除 |
| POST | `/api/admin/billboards/images/import` | 令牌 | 上传 zip 压缩包，解压校验并自动分类（multipart：file） |
| POST | `/api/admin/billboards/images/classify` | 令牌 | 直接多选图片，校验并自动分类（multipart：files） |
| DELETE | `/api/admin/billboards/images/import/{sessionId}` | 令牌 | 清理未保存的导入临时目录 |
| POST | `/api/knowledge/ask` | 公开 | 知识库问答（question、topK、model、history；价格 / 状态 / 位置类问题最多返回 20 条符合条件的参考广告牌） |
| POST | `/api/knowledge/ask/stream` | 公开 | 知识库问答 SSE 流式版本（question、topK、model、history；meta → delta → done，UTF-8） |
| GET | `/api/knowledge/models` | 公开 | 可切换模型列表（后台 API 管理线路，默认项标记） |
| POST | `/api/knowledge/ask/image/stream` | 公开 | 图片选牌 SSE（multipart：file、question、model、topK、history；视觉模型 OCR / 画面理解 + 本地视觉比对） |
| GET | `/api/admin/knowledge/status` | 令牌 | 知识库索引进度与逐条状态 |
| POST | `/api/admin/knowledge/rebuild` | 令牌 | 强制重建全部知识库索引 |
| GET | `/api/admin/apis` | 令牌 | API 配置列表（type 可选；Key 脱敏） |
| POST | `/api/admin/apis` | 令牌 | 新增 API 配置 |
| PUT | `/api/admin/apis/{id}` | 令牌 | 编辑配置（apiKey 留空保持原值，`clearApiKey=true` 清空） |
| DELETE | `/api/admin/apis/{id}` | 令牌 | 删除配置（默认项删除后自动接替） |
| POST | `/api/admin/apis/{id}/default` | 令牌 | 设为同类默认配置 |
| POST | `/api/admin/apis/{id}/test` | 令牌 | 测试已保存配置的连通性 |
| POST | `/api/admin/apis/test` | 令牌 | 保存前用表单值测试连通性 |
| GET | `/api/admin/db/databases` | 令牌 | 维护账号可见的数据库列表（含表 / 字段数） |
| GET | `/api/admin/db/databases/{database}/overview` | 令牌 | 指定数据库概览 / DDL 权限 |
| GET | `/api/admin/db/databases/{database}/tables` | 令牌 | 指定数据库全部表结构 |
| GET | `/api/admin/db/databases/{database}/search` | 令牌 | 指定数据库内检索（keyword、includeData） |
| GET | `/api/admin/db/search-all` | 令牌 | 跨数据库检索字段及所属库（不扫描数据） |
| POST | `/api/admin/db/databases/{database}/tables/{table}/columns` | 令牌 | 在任意表新增字段（ALTER TABLE ADD COLUMN） |
| GET | `/api/admin/db/overview` | 令牌 | 当前默认库概览 / DDL 权限状态 |
| GET | `/api/admin/db/tables` | 令牌 | 全部表结构（可传 keyword 过滤） |
| GET | `/api/admin/db/search` | 令牌 | 检索表 / 字段 / 注释 / 数据内容（keyword、includeData） |
| GET | `/api/admin/db/modules` | 令牌 | 数据库维护子模块列表 |
| POST | `/api/admin/db/modules` | 令牌 | 新建子模块 |
| PUT | `/api/admin/db/modules/{id}` | 令牌 | 编辑子模块（名称 / 说明 / 排序 / 启停） |
| DELETE | `/api/admin/db/modules/{id}` | 令牌 | 删除自定义子模块（无字段时） |
| GET | `/api/admin/db/billboard-columns` | 令牌 | billboard 表全部实际字段（系统字段 + 扩展字段，含注释 / 类型 / 可空性 / 启用状态 / 表单分区 / 栏宽 / 同步状态） |
| PUT | `/api/admin/db/billboard-columns/{name}` | 令牌 | 修改字段数据库注释；扩展字段同时更新显示名称 |
| PUT | `/api/admin/db/billboard-columns/{name}/definition` | 令牌 | 修改系统字段类型 / 长度 / 精度 / 可空性 / 注释 |
| GET | `/api/admin/db/modules/{id}/fields` | 令牌 | 子模块下的字段列表（含同步状态） |
| POST | `/api/admin/db/modules/{id}/fields` | 令牌 | 新增字段，同步执行 ALTER TABLE ADD COLUMN |
| PUT | `/api/admin/db/fields/{id}` | 令牌 | 编辑字段展示属性 |
| PUT | `/api/admin/db/fields/{id}/layout` | 令牌 | 轻量更新启用状态 / 表单分区 / 栏宽 / 排序 / 必填 / 前台展示，不触发表结构 |
| DELETE | `/api/admin/db/fields/{id}` | 令牌 | 删除字段，同步执行 DROP COLUMN |
| POST | `/api/admin/db/fields/{id}/sync` | 令牌 | 数据库列缺失时重新同步 |
| GET | `/api/admin/db/billboard-fields` | 令牌 | 广告牌表单字段定义（按子模块分组） |

管理接口令牌通过请求头 `X-Admin-Token` 携带；上传图片保存在 `uploads/yyyyMM/` 目录，经 `/uploads/**` 静态映射对外访问。

流式问答可直接用 curl 观察事件：

```bash
curl -N -X POST http://localhost:8080/api/knowledge/ask/stream \
  -H "Content-Type: application/json" \
  -d '{"question":"月租 3 万以内的可租赁广告牌","topK":5}'
```

会依次看到 `event:meta`、多条 `event:delta` 和最后的 `event:done`。

`history` 为可选的多轮对话数组，例如 `[{"role":"user","content":"京沪高速有哪些点位？"},{"role":"assistant","content":"..."}]`；前端自动携带最近 10 条消息。

图片选牌：

```bash
curl -N -X POST http://localhost:8080/api/knowledge/ask/image/stream -F "file=@test.png;type=image/png" -F "question=找相似风格的广告牌" -F "model=api-2" -F "topK=5"
```

返回的 `meta` 会额外包含 `imageMatch=true` 和 `visualFeatures`（主色 / 亮度 / 构图 / 已比对图片数）。

## 运行测试

```bash
mvn test
```

包含以下测试（H2 内存库，不需要 MySQL）：

- `BillboardServiceTest`：筛选 / 排序 / 关键词 / 编号唯一 / 软删除恢复等业务规则
- `AdminApiFlowTest`：登录鉴权 → 新增（含图片上传）→ 前台同步 → 编辑 → 删除 → 恢复 → 登出全链路
- `AdminTokenServiceTest`：令牌签发 / 校验 / 过期 / 作废
- `KnowledgeQueryAnalyzerTest`：价格 / 状态 / 位置等查询意图解析
- `ApiConfigApiTest`：API 管理鉴权、增删改、Key 脱敏 / 保留 / 清空、默认配置切换、地图 Key 实时生效与回退、参数校验、连通性测试失败结果
- `BillboardFieldServiceTest`：扩展字段 ALTER TABLE 同步、数据库可空性、非空字段插入兜底、已有系统字段与扩展字段合并、数据库列注释同步、动态值读写（文本 / 下拉 / 数字 / 布尔 / 日期）、必填与选项校验、删除列、多子模块管理
- 系统字段结构编辑（类型 / 长度 / 可空性）通过 UI 与真实 MySQL 联调验证，`name` VARCHAR(100)→VARCHAR(120)→TEXT→VARCHAR(100) 数据保持不变
- 字段重命名 / 类型变更通过 UI 与真实 MySQL 联调验证：`ALTER TABLE ... CHANGE COLUMN` 后已有字段值保持不变
- 字段管理 / 表单设计通过 UI 与真实 MySQL 联调验证：启停字段即时影响后台表单和公开字段接口，必填 / 控件类型 / 下拉选项 / 分区 / 栏宽 / 拖拽排序 / 新增字段均落库并驱动后台广告牌表单
- `MultiDatabaseMaintenanceServiceTest`：数据库列表、按库读取表结构、跨库检索字段所属库、任意表新增字段、billboard 专用字段保护
- `DatabaseMaintenanceServiceTest`：表结构读取、字段名检索、表内数据内容检索与数据库概览
- `ImageClassificationServiceTest`：宣传 / 实景 / 图纸的文件名关键词、宽高比、白底留白分类与低置信度兜底
- `BillboardArchiveImportServiceTest`：多级目录、非图片过滤、Zip Slip 防护、单图大小 / 数量限制、临时目录清理
- `BillboardImageImportApiTest`：压缩包导入 → 手动改分类 / 设封面 → 保存落库 → 前台详情与图片访问的接口级闭环
- `SequenceServiceTest`：合同号 / 预定号原子分配（连续递增、不同名称互不影响）

前端联调验证脚本（需先启动后端，脚本会自动清理测试数据）：

```bash
node verify-vue-e2e.mjs         # 接口 + 静态资源 + 前后台同步闭环（42 项）
node verify-vue-admin-ui.mjs    # 无头浏览器渲染后台页面并模拟真实表单录入（24 项）
node verify-infowindow.mjs      # 地图图钉 → 信息窗 → 详情弹窗的前台联动（9 项）
node verify-view-switch.mjs     # 首页"广告牌 / 地图定位"视图切换、卡片定位联动、地图视野稳定性（18 项）
node verify-admin-session.mjs   # 管理入口登录会话：首次验证 / 前后台免密切换 / 退出后恢复（14 项）
node verify-db-maintenance.mjs  # 数据库维护页：数据库切换 / 跨库检索 / 字段编辑 / 任意表新增字段 / 字段管理启停 / 表单设计编辑模式·字段库·必填·下拉选项·拖拽·新增字段 / 后台表单联动（102 项）
node capture-screens.mjs        # 批量截取首页列表视图 / 首页地图视图 / 后台 / 详情弹窗，用于文档与论文插图
```

> `mvn test` 的测试环境默认设置 `app.rag.enabled=false`，不会调用本地向量服务；
> 知识库功能请先按“快速启动”启动 `rag-service`，再通过页面或 `/api/knowledge/ask` 验证。

## 目录结构

```
jqgg_contract/
├── pom.xml                          # Maven 配置
├── sql/init.sql                     # 数据库初始化脚本（建库建表 + 账号 + 示例数据）
├── sql/db-maintenance.sql           # 已有部署升级：数据库维护表 + ALTER 授权（幂等）
├── sql/db-maintenance-user.sql      # 可选：跨库维护账号（SELECT / ALTER / SHOW DATABASES）
├── config/                          # 本地私有配置（模型 Key 等，已 gitignore，可选）
├── frontend-pc/                     # 电脑端 Vue 3 工程（服务根路径 /）
│   ├── package.json / vite.config.js
│   ├── public/img/samples/          # 示例广告牌图片
│   └── src/                         # 路由 / 页面 / 组件 / API 封装
├── frontend-mobile/                 # 手机端 Vue 3 工程（服务路径 /m/）
│   ├── package.json / vite.config.js
│   └── src/                         # 手机 App 化布局，底部 dock / 筛选弹层 / 抽屉
├── rag-service/                     # 本地 bge-m3 ONNX 向量服务（Ollama 兼容接口）
│   ├── embed_server.py / requirements.txt
│   ├── download-model.bat / start-embedding.bat
│   └── model/                       # 模型文件（约 2.3GB，已加入 .gitignore）
├── src/main/java/com/jqgg/glm53/
│   ├── JqggApplication.java         # 启动类
│   ├── config/                      # 应用配置 / 鉴权拦截器 / MVC 配置
│   ├── controller/                  # 公开接口 + 后台管理接口
│   ├── dto/                         # 视图对象 / 表单 / 统一响应
│   ├── entity/                      # JPA 实体与状态枚举
│   ├── exception/                   # 业务异常与全局异常处理
│   ├── repository/                  # Spring Data JPA 数据访问
│   └── service/                     # 业务服务 / 令牌服务 / 文件存储服务
├── src/main/resources/
│   ├── application.yml              # 应用配置
│   └── static/                      # 前端构建产物（由 npm run build 生成）
└── src/test/java/                   # 测试
```

## 常见问题

| 现象 | 处理方式 |
|---|---|
| 端口 8080 被占用（如同机运行其他项目） | `SERVER_PORT=8081` 环境变量换端口后重启 |
| MySQL 账号有密码 | 启动前设置 `DB_PASSWORD` 环境变量 |
| 地图不显示 | 检查浏览器联网；确认腾讯地图 Key 有效（`MAP_AK` 可覆盖）；列表功能不受地图影响 |
| 智能选牌提示“本地向量模型不可用” | 到后台「API 管理」检查向量模型的地址与模型是否可用（可点“测试连接”）；本地模型请启动 `rag-service\start-embedding.bat`（或 `ollama serve`），确认 `http://127.0.0.1:11434` 可访问，然后在后台“知识库”页点击“重建全部索引” |
| 智能选牌只返回列表、没有自然语言回答 | 未配置可用的问答模型；到后台「API 管理」新增 / 启用检索生成模型并填写 Key 与模型名（数据库配置优先），或仅在没有数据库线路时写入 `config/application.yml` 的 `app.rag.llm.api-key` / 设置 `LLM_API_KEY` 作为兜底 |
| 后台「API 管理」提示 `api_config` 表不存在 | 重新执行 `sql/init.sql` 补齐表结构（`CREATE TABLE IF NOT EXISTS`，不会清空已有数据），然后刷新页面 |
| 后台「数据库维护」提示 `sys_maintenance_module` / `billboard_field` 表不存在 | 以 root 执行 `sql/db-maintenance.sql` 补齐维护表并授权，然后重启应用 |
| 数据库维护只显示 `jqgg_glm53` 一个库 | 应用主账号只有该项目库权限；执行 `sql/db-maintenance-user.sql` 创建维护账号，并用 `DB_MAINT_USERNAME` / `DB_MAINT_PASSWORD` 重启应用 |
| 在其他库新增字段提示缺少 ALTER 权限 | 给维护账号授予目标库的 `ALTER`（查询还需要 `SELECT`，列出数据库需要 `SHOW DATABASES`）；生产环境建议按库授权 |
| “数据表结构”里 billboard 表不能直接新增字段 | 设计如此：项目 billboard 表请使用“广告牌字段 / 广告牌属性”子模块，才能同步接入后台表单、前台详情和知识库 |
| 跨库检索结果没有数据内容命中 | 跨库检索只查字段 / 注释 / 类型；表内数据扫描请先选择具体数据库，并勾选“同时检索表内数据内容” |
| 新增广告牌字段提示“数据库新增字段失败 / 缺少 ALTER 权限” | 用 root 执行 `GRANT ALTER ON jqgg_glm53.* TO 'jqgg_glm53_user'@'localhost';`（或重新执行 `sql/db-maintenance.sql`），然后在字段列表点击“同步” |
| 新增字段后广告牌表单或前台详情没有显示 | 确认字段所属子模块处于“已启用”，并到“字段管理”确认字段本身也已启用；前台展示还需勾选字段的“在前台广告牌详情中展示”。保存广告牌后前台 15 秒内同步 |
| 字段管理里系统字段不能切换分区 / 栏宽 | 系统字段分区由业务实体固定；扩展字段才支持表单分区、栏宽和排序。系统字段的类型 / 长度 / 可空性请到“广告牌字段”子模块编辑 |
| 停用字段后数据还在吗 | 在，停用只把 `billboard_field.enabled` 置 0，不执行 `DROP COLUMN`；重新启用即恢复表单和前台展示。只有删除字段才会删列 |
| 修改向量模型后索引仍是旧状态 | 保存向量模型配置会自动异步重建；若模型服务不可用会标记失败，修复地址后到“知识库”页点击“重建全部索引” |
| 智能选牌漏掉某些广告牌或价格比较不完整 | 确认运行的是最新版本：价格 / 状态 / 位置类问题会走全量结构化筛选，不再只看向量 topK；纯语义问题可适当调低 `RAG_MIN_SCORE`（默认 0.5） |
| 图片选牌提示“仅支持 PNG 或 JPEG” | 前端和后端只接受位图 PNG/JPEG；SVG 不允许上传（广告牌示例 SVG 由后端 Batik 光栅化） |
| 图片相似度分数相同或偏高 | 多条广告牌可能共用同一张示例图片，分数相同是正常的；该功能是本地视觉特征（颜色 / 结构 / 构图）比对，不是外部视觉大模型识别 |
| 图片上传后没有落盘 | 设计如此：图片只在内存中用于比对，不保存到 uploads |
| 模型下拉只有一项 | 后台「API 管理」只启用了一条检索生成线路；新增 / 启用其他线路后刷新前台 |
| 问答答案直接显示 `**` 或表格竖线 | 前端未加载最新构建产物；执行 `npm run build` 后强刷。前端已用 marked 渲染 Markdown，并经 DOMPurify 清洗 |
| 问答答案中途截断或末尾留下未闭合的 `**` | 回答超过模型 token 上限；调大 `LLM_MAX_TOKENS`（默认 2000），或让模型用表格 / 紧凑列表输出 |
| 流式回答没有逐字效果，最后一次性出现 | 反向代理或压缩层缓冲了 SSE；确认代理关闭缓冲并保持 chunked（后端已设置 `X-Accel-Buffering: no`），或直连应用端口测试 |
| 页面中文显示为乱码 | 后端 JSON 已声明 `charset=UTF-8`；检查浏览器或反向代理是否强制了其他编码，`Ctrl+Shift+R` 强刷一次 |
| bge-m3 模型下载慢 | 默认从 ModelScope 下载（实测约 8MB/s）；模型约 2.3GB，支持断点续传，文件位于 `rag-service/model/` |
| 浏览器中页面没有样式 / 样式错乱 | 浏览器缓存了旧的 `index.html`（其引用的旧 hash 资源已不存在）：按 `Ctrl+Shift+R` 强制刷新一次即可。服务端已对静态资源启用 `no-cache` 校验，之后不会再复用过期入口页 |
| 控制台中文日志乱码 | `set MAVEN_OPTS=-Dfile.encoding=UTF-8` 后再 `mvn spring-boot:run` |
| 上传的图片存放位置 | 项目根目录 `uploads/yyyyMM/`，编辑移除图片仅解除关联，物理文件保留 |
| 后台修改后前台何时生效 | 保存即时落库，前台最迟 15 秒自动同步（也可刷新页面立即生效） |
