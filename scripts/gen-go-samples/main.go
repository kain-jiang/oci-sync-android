// 可复现的 Go 互操作样本生成器(独立实现,不依赖上游仓库,遵循 docs/02-core-format.md 规范)。
// 用法:go run scripts/gen-go-samples/main.go <输出目录>
// 产物(go-pack.tgz / go-encrypt.bin)提交到 core/src/test/resources/interop/,
// 供 Android 端 InteropTest 做字节级兼容回归。
package main

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"fmt"
	"io"
	"os"
	"path/filepath"

	"golang.org/x/crypto/scrypt"
)

const (
	passphrase = "interop-passphrase-2026"
	plaintext  = "go-encrypted-secret-payload-42"

	saltSize  = 32
	nonceSize = 12
	scryptN   = 1 << 15
	scryptR   = 8
	scryptP   = 1
)

func main() {
	outDir := os.Args[1]
	if err := os.MkdirAll(outDir, 0o755); err != nil {
		panic(err)
	}

	// 1. 打包样本:与上游 archive.Pack 行为一致(gzip 零 mtime、目录条目以 / 结尾)
	tmpDir, err := os.MkdirTemp("", "gosample")
	if err != nil {
		panic(err)
	}
	defer os.RemoveAll(tmpDir)
	root := filepath.Join(tmpDir, "sample")
	must(os.MkdirAll(filepath.Join(root, "sub", "empty-dir"), 0o755))
	must(os.WriteFile(filepath.Join(root, "hello.txt"), []byte("hello from go\n"), 0o644))
	must(os.WriteFile(filepath.Join(root, "sub", "nested.txt"), []byte("nested content 42\n"), 0o644))

	var buf bytes.Buffer
	gw := gzip.NewWriter(&buf)
	tw := tar.NewWriter(gw)
	must(filepath.Walk(root, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		rel, err := filepath.Rel(root, path)
		if err != nil {
			return err
		}
		name := filepath.Join("sample", rel)
		if info.IsDir() {
			name += "/"
		}
		hdr, err := tar.FileInfoHeader(info, "")
		if err != nil {
			return err
		}
		hdr.Name = name
		if err := tw.WriteHeader(hdr); err != nil {
			return err
		}
		if info.IsDir() {
			return nil
		}
		f, err := os.Open(path)
		if err != nil {
			return err
		}
		defer f.Close()
		_, err = io.Copy(tw, f)
		return err
	}))
	must(tw.Close())
	must(gw.Close())
	must(os.WriteFile(filepath.Join(outDir, "go-pack.tgz"), buf.Bytes(), 0o644))
	fmt.Printf("go-pack.tgz: %d bytes\n", buf.Len())

	// 2. 加密样本:与上游 crypto.Encrypt 一致
	salt := make([]byte, saltSize)
	must(readFull(salt))
	key, err := scrypt.Key([]byte(passphrase), salt, scryptN, scryptR, scryptP, 32)
	if err != nil {
		panic(err)
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		panic(err)
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		panic(err)
	}
	nonce := make([]byte, nonceSize)
	must(readFull(nonce))
	ct := gcm.Seal(nil, nonce, []byte(plaintext), nil)
	out := append(append(salt, nonce...), ct...)
	must(os.WriteFile(filepath.Join(outDir, "go-encrypt.bin"), out, 0o644))
	fmt.Printf("go-encrypt.bin: %d bytes\n", len(out))

	// 自检
	dec, err := decrypt(out, passphrase)
	if err != nil || string(dec) != plaintext {
		panic(fmt.Sprintf("self-check failed: %v", err))
	}
	fmt.Println("self-check OK")
}

func decrypt(data []byte, pass string) ([]byte, error) {
	key, err := scrypt.Key([]byte(pass), data[:saltSize], scryptN, scryptR, scryptP, 32)
	if err != nil {
		return nil, err
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	return gcm.Open(nil, data[saltSize:saltSize+nonceSize], data[saltSize+nonceSize:], nil)
}

func readFull(b []byte) error {
	_, err := io.ReadFull(rand.Reader, b)
	return err
}

func must(err error) {
	if err != nil {
		panic(err)
	}
}
