#ab -T "text/plain" -p body.json -n 50000 -c 5000 http://localhost:8080/mock-work
wrk -t8 -c400 -d15s --timeout 2s http://localhost:8080/mock-work
