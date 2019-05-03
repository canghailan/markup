# Markup - 微型Markdown文档服务器

所有文档都缓存在内存中，针对少量文档（博客、接口文档）场景优化

## 预览
![screenshot](doc/screenshot.jpg)

## 目录结构
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

## 接口
### 首页
```http
GET /
```
```http
GET /index.html
```

### 文件
与版本库文件路径一致
```http
GET /**/*.*
```

### 目录（Table Of Content）
```http
GET /.toc
```

### 全文搜索
搜索参数：
* p 目录
* q 关键词，有关键词结果按相关性排序，无关键词结果按文件创建时间倒序排序
* n 分页大小
* c 分页标识

搜索响应：
* list 搜索数据
* cursor 分页标识，null表示已到最后一页
#### 首次请求
```http
GET /.s?p=&q=&n=

{
  "list": [],
  "cursor": "CURSOR"
}
```
#### 下一页
```http
GET /.s?c=CURSOR

{
  "list": [],
  "cursor": ""
}
```

### 更新
```http
GET /.updater
```


## 依赖
* [jgit](https://github.com/eclipse/jgit) - 从远程Git仓库读取文件
* [commonmark](https://github.com/atlassian/commonmark-java) - 将Markdown转为HTML
* [lucene](https://github.com/apache/lucene-solr) - 文档存储、搜索
* [HanLP](https://github.com/hankcs/HanLP)、[hanlp-lucene-plugin](https://github.com/hankcs/hanlp-lucene-plugin) - 中文分词、拼音
* [jackson](https://github.com/FasterXML/jackson) - 配置文件、接口数据
* [netty](https://github.com/netty/netty) - HTTP服务器

## License
MIT