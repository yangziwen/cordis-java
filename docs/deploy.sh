#!/usr/bin/env sh

set -e

# 从 ../course 同步最新教程文档后再构建
npm run docs:build

cd docs/.vuepress/dist

git init
git add -A
git commit -m 'deploy'

git push -f git@github.com:yangziwen/cordis-java.git main:gh-pages

cd -
