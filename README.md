# NanoLimbo

### 自动构建server.jar指南

1：fork本项目

2：在Actions菜单允许 `I understand my workflows, go ahead and enable them` 按钮

  
- [yml工作流](./.github/workflows/build-jar.yml)

    yml工作流文件31行可以修改上传到Release的文件名称

3：点击跳转Nanolimbo.java
- [Nanolimbo.java](./src/main/java/ua/nanit/limbo/NanoLimbo.java)


     75到76行是关于komari探针变量
     
     125到142 行中添加需要的环境变量，不需要的留空，保存后Actions会自动构建

4：等待2分钟左右，在右侧的Release里下载server.jar文件
