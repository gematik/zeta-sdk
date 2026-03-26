#!/bin/bash
BASE_URL="http://0.0.0.0:8080"
INSTANCE_COUNT=10
FACHDIENST_PATH="hellozeta"

CREATE_RESPONSE=$(curl -s -X POST "$BASE_URL/load/create_instances?count=$INSTANCE_COUNT&autoInit=true")
echo "$CREATE_RESPONSE"

while true; do
    LIST=$(curl -s "$BASE_URL/load/list_instances")
    NOT_READY=$(echo "$LIST" | grep -v '"state":"READY"' | grep '"state"' | wc -l)
    if [ "$NOT_READY" -eq 0 ]; then
        break
    fi
    echo "$NOT_READY"
    sleep 2
done

for i in $(seq 1 $INSTANCE_COUNT); do
    curl -s -o "/tmp/response_$i.txt" -w "%{http_code}" \
        -H "Host: zeta-staging.spree.de" \
        "$BASE_URL/load/$i/$FACHDIENST_PATH" &
done

wait

for i in $(seq 1 $INSTANCE_COUNT); do
    echo "Instance $i: $(cat /tmp/response_$i.txt)"
done

curl -s -X DELETE "$BASE_URL/load/delete_instances"
