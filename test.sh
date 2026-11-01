#!/bin/zsh
# Test harness: drives the highlighter like a remote. Usage: TV=<ip:port> test.sh key <name>... | state | shot <file>
D=${TV:?set TV to the TV adb address, e.g. TV=192.168.1.50:5555}
typeset -A K; K=(up 19 down 20 left 21 right 22 ok 23 back 4 holdback -1)
key() {
  local code=${K[$1]}
  local r=$(adb -s $D shell am broadcast -a com.osura.focushl.TEST_KEY --ei code $code 2>/dev/null | grep -o 'result=[0-9]*')
  if [[ $r != "result=1" && $code -ge 0 ]]; then adb -s $D shell input keyevent $code; echo "$1 -> app"; else echo "$1 -> highlighter"; fi
  sleep ${PAUSE:-0.8}
}
state() { adb -s $D shell am broadcast -a com.osura.focushl.TEST_STATE 2>/dev/null | sed -n 's/.*data="\(.*\)"/\1/p'; }
case $1 in
  key) shift; for k in "$@"; do key $k; state; done ;;
  state) state ;;
  shot) adb -s $D exec-out screencap -p > $2 ;;
esac
