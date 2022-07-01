#!/bin/bash
branch=`git branch --show-current`
echo  "fetching on branch $branch ..."
file="$branch".gitlog
git log --oneline --all > $file
