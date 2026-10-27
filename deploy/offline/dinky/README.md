# dinky 离线部署

完整 Dinky Java 11 / Flink 1.18.1 运行依赖，作为 Kubernetes Deployment 部署。

本包版本：1.3.0-SNAPSHOT，架构 linux/amd64。总步骤见根目录 README.md，配置共用根目录 .env。

先在每个可能调度节点运行 `sudo bash common/load-images.sh dinky k3s`。其他 containerd 使用 containerd 参数；只 docker load 不能给 K3s 导入镜像。
