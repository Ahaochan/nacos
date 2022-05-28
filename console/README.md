1. 执行`mvn clean install -Dmaven.test.skip=true -Dcheckstyle.skip=true`
2. 启动[Naocs](./src/main/java/com/alibaba/nacos/Nacos.java)类, 添加`VM options`环境变量`-Dnacos.standalone=true`
3. 如果出现类丢失, 就去对应模块的`\target\generated-sources\protobuf`, 将下面的每个目录都标记为`generated sources root`
