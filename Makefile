.PHONY: local-test
local-test:
	curl -X POST http://localhost:1234/test/run \
		-H "Content-Type: application/json" \
		-d '{"serviceName":"$(PAYMENT_SERVICE_NAME)","token":"$(PAYMENT_TOKEN)","ratePerSecond":1,"testCount":100,"processingTimeMillis":80000}'
	curl -X POST http://localhost:1234/test/stop/$(PAYMENT_SERVICE_NAME)

.PHONY: infra run

infra:
	docker compose -f docker-compose.yml up

run:
	mvn spring-boot:run


.PHONY: remote-test remote-stop

# Defaults for remote testing (can be overridden on CLI)
branch ?= main
accounts ?= acc-12,acc-20
ratePerSecond ?= 2
testCount ?= 10
processingTimeMillis ?= 80000

remote-test:
	curl -v -X POST http://77.234.215.138:34321/run \
		-H "Content-Type: application/json" \
		-d '{"serviceName":"$(PAYMENT_SERVICE_NAME)","token":"$(PAYMENT_TOKEN)","branch":"$(branch)","accounts":"$(accounts)","ratePerSecond":$(ratePerSecond),"testCount":$(testCount),"processingTimeMillis":$(processingTimeMillis),"onPremises":true}'

remote-stop:
	curl -X POST http://77.234.215.138:31234/test/stop/$(PAYMENT_SERVICE_NAME)

