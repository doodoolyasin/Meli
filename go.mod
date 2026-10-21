module github.com/doodoolyasin/meli

go 1.24.0

replace github.com/doodoolyasin/meli => ./

require (
	github.com/BurntSushi/toml v1.4.0
	github.com/klauspost/compress v1.18.5
	github.com/klauspost/reedsolomon v1.12.4
	github.com/pierrec/lz4/v4 v4.1.26
	golang.org/x/crypto v0.42.0
	golang.org/x/net v0.43.0
	golang.org/x/sys v0.36.0
)

require github.com/klauspost/cpuid/v2 v2.3.0 // indirect
