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

# Defaults for remote testing (can be overridden on CLI)
branch ?= feature/hw-2
accounts ?= acc-5
ratePerSecond ?= 2
testCount ?= 500
processingTimeMillis ?= 60000 

remote-test:
	curl -v -X POST http://77.234.215.138:34321/run \
		-H "Content-Type: application/json" \
		-d '{"serviceName":"$(PAYMENT_SERVICE_NAME)","token":"$(PAYMENT_TOKEN)","branch":"$(branch)","accounts":"$(accounts)","ratePerSecond":$(ratePerSecond),"testCount":$(testCount),"processingTimeMillis":$(processingTimeMillis),"onPremises":true}'

remote-stop:
	curl -X POST http://77.234.215.138:31234/test/stop/$(PAYMENT_SERVICE_NAME)

