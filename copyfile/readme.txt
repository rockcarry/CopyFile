+------------------+
 新版 copyfile 工具
+------------------+

+--------+
 使用说明
+--------+

copyfile 工具用于工厂量产时，执行地图数据拷贝，文件拷贝、目录拷贝、预装 apk 等。

新版 copyfile 工具，增加了拷贝时的文件大小检验，可防止量产中拷贝时出现的文件丢失，文件拷贝不全等问题。


+--------+
 使用方法
+--------+
正确配置 config.ini 文件，然后把 copyfile 目录，放入 sd 卡根目录。同时把要拷贝的源文件、目录和 apk 也放入 sd 卡。
机器开机后插 sd 卡，会自动执行 copyfile 动作。


+--------+
 配置文件
+--------+
config.ini 是拷贝工具的配置文件

示例：
[dir]
src=/mnt/extsd/Navi/
dst=/mnt/sdcard/.Navi/

[dir]
src=/mnt/extsd/Movies/
dst=/mnt/sdcard/Movies/

[dir]
src=/mnt/extsd/Music/
dst=/mnt/sdcard/Music/

[apk]
src=/mnt/extsd/apk/

配置文件，可以有 [dir] [file] [apk] 三个配置选项
每个配置选项，对于了一个拷贝任务

[dir] 配置选项，用于配置目录拷贝，可以把 src 目录的文件，拷贝至 dst 目录

[file] 配置选项，用于配置文件拷贝，可以吧 src 文件，拷贝至 dst 文件

[apk] 配置选项，用于配置 apk 安装，可以自动预装 src 目录下的所有 apk 文件


+--------+
 拷贝检验
+--------+
[dir] 和 [file] 可以配置校验，例如：

[dir]
src=/mnt/extsd/Navi/
dst=/mnt/sdcard/.Navi/
checksum=265648

checksum 用于指定目录（或文件）大小，字节为单位

dir 下如果增加了 checksum 这个选项，那么拷贝前会检查源目录（即 src 指定的目录）的大小
并且拷贝完成后，还会检查目标目录（即 dst 指定的目录）的大小

只有当源和目的目录的大小，都检查通过，才会提示拷贝成功，否则会提示拷贝失败。




apical chenk
2018-3-20






