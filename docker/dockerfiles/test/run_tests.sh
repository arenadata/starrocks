#!/bin/bash
# Runs every *_test.sh in this directory. No dependencies beyond bash and coreutils.

cd "$(dirname "${BASH_SOURCE[0]}")" || exit 1

status=0
for suite in *_test.sh ; do
    if ! bash "$suite" ; then
        status=1
    fi
    echo
done

if [[ $status -ne 0 ]] ; then
    echo "FAILED"
else
    echo "OK"
fi
exit $status
