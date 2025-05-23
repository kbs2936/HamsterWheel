# HamsterWheel

HamsterWheel is a mouse sensor test and benchmark tool. Use it to test the performance of your mouse sensor or buttons and the speed of your movements.

I created HamsterWheel just for fun so I can benchmark my own mice but feel free to use it for anything and fork it if you would like.

![alt text](https://github.com/szabodanika/HamsterWheel/blob/master/screenshot1.jpg?raw=true[/img])

## Features

- Polling rate testing
- DPI accuracy testing
- Speed and acceleration testing
- Skipping, jumping testing
- Cursor tracking
- Generating statistics in a log file
- Relative click latency test
- Click duration and interval test
- Full screen and windowed mode
- Polling rate multiplier for testing different polling rates
- Saving settings in local config file
- Dark mode
- RGB

## How to download and run

1. Find the newest release [here](https://github.com/szabodanika/HamsterWheel/releases)
2. Open the **assets** dropdown
3. Download **HamsterWheel.zip**
4. Unzip folder
5. Launch **HamsterWheel.exe**

## How to use

- Video and written tutorials coming soon

## Building the executable for yourself

### 1. Build executable jar with dependencies using Maven
```
mvn clean install
```

### 2. Generate custom JRE using jlink (optional, a full fat JDK can be used too)
```
jlink --output hamsterwheel-jre-runtime --add-modules java.desktop
```

### 3. Create native windows executable
1. On windows you can use launch4j for this, just load the execonfig.xml
2. Generate executable
3. Place executable in a new empty folder called "HamsterWheel"
4. Place custom JRE from previous step next to the .exe file

### 4. 制作 exe 可执行文件简要说明
1. 先安装 JDK 环境
2. VSCode 导出 jar 包，主类选 Controller，下一步选 target/classes，导出后可以测试一下jar包  java -jar xxx.jar
3. 打开 Launch4j.exe 的 Basic 页面填写一下生成文件的路径.exe 和 选一下上一步生成的 jar 包路径，再点击上部设置图标选一下根目录下的 execonfig.xml文件即可生成单个可执行的exe文件
