#!/usr/bin/env bash
set -o history -o histexpand

wait_for_docker() {

  while [ -z ${docker_running+x} ]; do
    sleep 1
    {
      echo "Checking for running docker..."
      docker ps -q && docker_running=1
    } || {
      echo "Docker is not running. Please start docker on your computer"
    }
  done
}

native() {
  echo "Building using docker graal"
  if [ -z "$(command -v docker)" ]; then
    echo "\"docker\" not found, please install docker"
    exit 1
  fi
  wait_for_docker
  docker run -it -v "$(pwd):/project" --rm richarddavison/graalvm-aws-linux2 --static "$@"
}

native_arguments=("$@")
index=$(echo "${native_arguments[@]/--args//}" | cut -d/ -f1 | wc -w | tr -d ' ')
native_arguments=("${native_arguments[@]:$((index + 1))}")

if [[ $1 == "local" ]]; then

  if [ -z ${GRAALVM_HOME+x} ]; then
    echo -e "evirontment variable GRAALVM_HOME is not set, try setting suing:\nexport GRAALVM_HOME=$HOME/Library/Graal/Contents/Home"
    exit 1
  fi

  unset -f native
  native() {
    echo "Building using local graal"

    export JAVA_HOME=${GRAALVM_HOME}
    export PATH=${JAVA_HOME}/bin:$PATH
    if [ -z "$(command -v native-image)" ]; then
      echo "\"native-image\" not found, installning native-image"
      gu install native-image
    fi
    native-image "$@"
  }
fi

# shellcheck disable=SC2012
native -jar "build/libs/$(ls -Slh ./build/libs | sed -n 2p | tr -s ' ' | cut -d ' ' -f9)" \
  --no-server \
  --enable-all-security-services \
  "${native_arguments[@]}"

exit_code=$?
if [[ ${exit_code} -ne 0 ]]; then
  echo >&2 "Compilation failed with exit code ${exit_code}."
  exit "${exit_code}"
fi

du -h runtime
