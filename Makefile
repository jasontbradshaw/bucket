all: watch

watch:
	reflex -r '\.go$$' -s godep go run bucket.go "${HOME}"

ui:
	cd assets && lein figwheel
