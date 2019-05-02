# Markup - 微型Markdown文档服务器

所有文档都缓存在内存中，针对少量文档（博客、接口文档）场景优化

# 目录结构
对目录结构没有特殊要求，.开头文档无法通过HTTP访问，首页index.html可自行定制
```shell
markup.jar # 单一执行文件
markup.yml # 配置文件
[repo] # Git文档库
.git
index.html
[DIRECTORY]
    *.md
    *.jpg
*.md 
...
```

# 接口
## 首页
```http
GET /
```
```http
GET /index.html
```

## 文件
与版本库文件路径一致
```http
GET /**/*.*
```

## 文件目录（Table Of Content）
```http
GET /.toc
```

## 全文搜索
搜索参数：
* p 目录
* q 关键词，有关键词结果按相关性排序，无关键词结果按文件路径排序（TODO：按文件创建时间倒序排序）
* n 分页大小
* c 分页标识

搜索响应：
* list 搜索数据
* cursor 分页标识，null表示已到最后一页
### 首次请求
```http
GET /.s?p=&q=&n=

{
  "list": [],
  "cursor": "CURSOR"
}
```
### 下一页
```http
GET /.s?c=CURSOR

{
  "list": [],
  "cursor": ""
}
```

## TODO：更新
```http
GET /.updater
```


# 依赖
* jgit - 从远程Git仓库读取文件
* commonmark - 将Markdown转为HTML
* Lucene - 文档存储、搜索
* HanLP - 中文分词、拼音
* Jackson - 配置文件、接口数据
* Netty - HTTP服务器