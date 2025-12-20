.PHONY: local-test
local-test:
	curl -v -X POST http://localhost:1234/test/run \
		-H "Content-Type: application/json" \
		-d '{"serviceName":"$(serviceName)","token":"$(token)","ratePerSecond":1,"testCount":100,"processingTimeMillis":80000}'

.PHONY: infra run logs

infra:
	docker compose -f docker-compose.yml up

logs:
	docker compose logs --tail=400 -f $(CONTAINER)

run:
	mvn spring-boot:run


.PHONY: run-local
run-local:
	PAYMENT_SERVICE_NAME=$(serviceName) PAYMENT_TOKEN=$(token) mvn spring-boot:run


.PHONY: remote-test remote-stop

branch ?= hw-9_last
accounts ?= acc-12
ratePerSecond ?= 1000
testCount ?= 200000
processingTimeMillis ?= 50000 

remote-test:
	curl -v -X POST http://77.234.215.138:34321/run \
		-H "Content-Type: application/json" \
		-d '{"serviceName":"$(PAYMENT_SERVICE_NAME)","token":"$(PAYMENT_TOKEN)","branch":"$(branch)","accounts":"$(accounts)","ratePerSecond":$(ratePerSecond),"testCount":$(testCount),"processingTimeMillis":$(processingTimeMillis),"onPremises":true}'

remote-stop:
	curl -X POST http://77.234.215.138:31234/test/stop/$(PAYMENT_SERVICE_NAME)

