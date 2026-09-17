#!/usr/bin/env bash
# 平台 CI 门禁（本地与流水线共用）：编译 + 单测 + 规范扫描
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
FAIL=0

echo "== [1/3] 编译公共模块 =="
mvn -q -pl my-xhs-common -am -DskipTests package || FAIL=1

echo "== [2/3] 公共模块单测 =="
mvn -q -pl my-xhs-common test -DfailIfNoTests=false || FAIL=1

echo "== [3/3] 规范扫描（my-xhs-common） =="
if grep -rn "printStackTrace()" my-xhs-common/src/main/java; then echo "❌ 禁止 printStackTrace()"; FAIL=1; fi
if grep -rn "System\.out\.println" my-xhs-common/src/main/java; then echo "❌ 禁止 System.out.println"; FAIL=1; fi
if grep -rn "@Disabled" my-xhs-common/src/test/java; then echo "❌ 禁止提交 @Disabled 测试"; FAIL=1; fi

if [ "$FAIL" = "0" ]; then
  echo "== ✅ 门禁通过 =="
else
  echo "== ❌ 门禁失败 =="; exit 1
fi
