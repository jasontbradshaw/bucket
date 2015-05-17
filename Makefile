all: watch

watch:
	reflex -r '\.go$$' -s godep go run *.go "${HOME}"
