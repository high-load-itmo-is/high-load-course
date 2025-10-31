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
branch ?= feature/hw-5
accounts ?= acc-23
ratePerSecond ?= 15
testCount ?= 3000 
processingTimeMillis ?= 2500 

# accounts ?= acc-23
# ratePerSecond ?= 11 
# testCount ?= 2200 
# processingTimeMillis ?= 13000 
# profile ?= "s_0.7_60"

# accounts ?= acc-23
# ratePerSecond ?= 3 
# testCount ?= 1050 
# processingTimeMillis ?= 26000 
# runits ?= 90

local-test:
	curl -v -X POST http://localhost:1234/test/run \
		-H "Content-Type: application/json" \
		-d '{"serviceName":"$(PAYMENT_SERVICE_NAME)","token":"$(PAYMENT_TOKEN)","ratePerSecond":$(ratePerSecond),"testCount":$(testCount),"processingTimeMillis":$(processingTimeMillis),"maxRetries":3,"retryCodes":[429],"timeout":"30s"}'

remote-test:
	curl -v -X POST http://77.234.215.138:34321/run \
		-H "Content-Type: application/json" \
		-d '{"serviceName":"$(PAYMENT_SERVICE_NAME)","token":"$(PAYMENT_TOKEN)","branch":"$(branch)","accounts":"$(accounts)","ratePerSecond":$(ratePerSecond),"testCount":$(testCount),"processingTimeMillis":$(processingTimeMillis),"onPremises":true}'

remote-stop:
	curl -X POST http://77.234.215.138:31234/test/stop/$(PAYMENT_SERVICE_NAME)

