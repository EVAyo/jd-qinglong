#!/bin/bash
set -e
detect_macos() {
    [[ $(uname -s) == Darwin ]] && is_macos=1 || is_macos=0
}

detect_macos

#当前目录
HOME=$(
  cd $(dirname $0)
  pwd
)

git pull

op=$1
if [[ $op == 'push' ]]; then
  echo "构建并推送镜像"
else
  op='local'
  echo "本地构建"
fi

if [ ! -f "$HOME/notify" ];then
  rm -rf qinglong && mkdir qinglong
  rm -rf notify
  git clone -b master --depth=1 https://github.com/whyour/qinglong.git
  cd qinglong/sample || exit

  if [[ $is_macos == 1 ]]; then
    sed -i '' 's/https:\/\/github.com\/whyour\/qinglong/https:\/\/github.com\/rubyangxg\/jd-qinglong/' notify.js
  else
    sed -i 's/https:\/\/github.com\/whyour\/qinglong/https:\/\/github.com\/rubyangxg\/jd-qinglong/' notify.js
  fi

  npm install
  cd ../shell || exit
  if [[ $is_macos == 1 ]]; then
    sed -i '' 's/\/ql\/scripts\/sendNotify.js/..\/sample\/notify.js/' notify.js
  else
    sed -i 's/\/ql\/scripts\/sendNotify.js/..\/sample\/notify.js/' notify.js
  fi

  pkg -t node12-linux-arm64 notify.js
  cp notify $HOME/notify
else
  echo "notify已存在"
fi

cd $HOME || exit
rm -rf .npm
rm -rf .pkg-cache
rm -rf qinglong

docker rm -f webapp
docker rmi -f rubyangxg/jd-qinglong:arm

if [ ! -f "$HOME/jd-qinglong-1.0.jar" ];then
  cd ..
  git pull
  mvn clean package -Parm -Dmaven.test.skip=true -f pom-os.xml
  cp target/jd-qinglong-*.jar $HOME
  cd $HOME || exit
else
  echo "jd-qinglong-1.0.jar已存在"
fi

docker build -t rubyangxg/jd-qinglong:arm --build-arg JAR_FILE=jd-qinglong-1.0.jar .
#docker build -t rubyangxg/jd-qinglong:1.1 --build-arg JAR_FILE=jd-qinglong-1.0.jar .
if [[ $op == 'push' ]]; then
  docker login
  docker push rubyangxg/jd-qinglong:arm
#  docker push rubyangxg/jd-qinglong:1.1
fi

cd /root/run
docker stop webapp && docker rm webapp
docker run -d -p 5701:8080 --name=webapp --privileged=true -v "$(pwd)"/env.properties:/env.properties:ro -v "$(pwd)"/go-cqhttp:/go-cqhttp rubyangxg/jd-qinglong:arm
