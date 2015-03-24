package main

import (
	"encoding/json"
	"fmt"
	"io/ioutil"
	"net/http"
	"os"
	"os/exec"
	"path"
	"path/filepath"
	"strings"

	"github.com/gorilla/mux"
)

var ROOT = ""

type TypeMapJSON struct {
	MIME        string `json:"mime"`
	IsDirectory bool   `json:"is_directory"`
	IsHidden    bool   `json:"is_hidden"`
	IsAudio     bool   `json:"is_audio"`
	IsImage     bool   `json:"is_image"`
	IsVideo     bool   `json:"is_video"`
}

type FileInfoJSON struct {
	Name       string      `json:"name"`
	Size       int64       `json:"size"`
	ModifiedAt string      `json:"modified_at"`
	Type       TypeMapJSON `json:"type"`
}

// returns a pair of (filename, MIME type) strings given a `file` output line
func parseMIMEType(fileOutputLine string) (string, string, error) {
	// parse the file program output into a bare MIME type
	mimeString := strings.TrimSpace(fileOutputLine)
	splitIndex := strings.LastIndex(mimeString, ":")

	if len(fileOutputLine) <= 1 || splitIndex <= 0 {
		return "", "", fmt.Errorf("Invalid MIME string: '%s'", fileOutputLine)
	}

	return mimeString[0:splitIndex], strings.TrimSpace(mimeString[splitIndex+1:]), nil
}

// given a path, returns a map of child name to MIME type
func getMIMETypes(root string, files []os.FileInfo) map[string]string {
	// build the command to get all the MIME types at once, for efficiency
	args := []string{"--mime-type", "--dereference", "--preserve-date"}
	for _, file := range files {
		args = append(args, path.Join(root, file.Name()))
	}

	// call `file` for a newline-delimited string of "filename: MIME-type" pairs
	result := make(map[string]string, len(files))
	fileOutput, err := exec.Command("file", args...).Output()
	if err != nil {
		return result
	}

	for _, line := range strings.Split(string(fileOutput), "\n") {
		fileName, mimeType, err := parseMIMEType(line)
		if err == nil {
			// use the full path, so we can handle multiple directories unambiguously
			result[fileName] = mimeType
		}
	}

	return result
}

func getFileOrDirectory(w http.ResponseWriter, r *http.Request) {
	// disable caching since we'll want to keep this listing up-to-date
	w.Header().Add("Cache-Control", "no-cache")

	// ensure the file/directory actually exists
	// TODO: see if it's a file or a directory, don't just assume a directory!
	requestPath, err := normalizePathToRoot(ROOT, mux.Vars(r)["path"])
	fmt.Println("requestPath:", requestPath)
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}

	children, err := ioutil.ReadDir(requestPath)
	if err != nil {
		// don't report the raw error in case we leak server directory information
		http.Error(w, "", 404)
		return
	}

	// get a map of all the MIME types for the given files
	mimeTypes := getMIMETypes(requestPath, children)
	fmt.Println(mimeTypes)

	// list the directory to a JSON response
	var files []FileInfoJSON
	for _, file := range children {
		fileName := file.Name()
		filePath := path.Join(requestPath, fileName)
		mimeType, _ := mimeTypes[filePath]
		isHidden := strings.HasPrefix(fileName, ".")

		// TODO: determine if it's one of these types!
		isAudio, isImage, isVideo := false, false, false

		files = append(files, FileInfoJSON{
			fileName,
			file.Size(),
			file.ModTime().Format("2006-01-02T15:04:05Z"), // ISO 8601
			TypeMapJSON{
				mimeType,
				file.IsDir(),
				isHidden,
				isAudio,
				isImage,
				isVideo,
			},
		})
	}

	json, err := json.Marshal(files)
	if err != nil {
		http.Error(w, "Failed to generate JSON response", 500)
		return
	}

	w.Header().Add("Content-Type", "application/json")
	w.Write(json)
}

// normalizes the path using a root, and returns it. if the path exits the root
// or is otherwise invalid, returns an error.
func normalizePathToRoot(root, child string) (string, error) {
	// clean the path, resolving any ".."s in it
	requestPath := path.Clean(path.Join(root, child))

	// if the path exited the root directory, fail
	relPath, err := filepath.Rel(root, requestPath)
	if err != nil || strings.Index(relPath, "..") >= 0 {
		// keep things vague since someone's probably trying to be sneaky anyway
		return "", fmt.Errorf("Invalid path")
	}

	return requestPath, nil
}

// retrieves/caches/updates a thumbnail file given a path, or returns an error
// if no thumbnail could be geneated.
func getThumbnail(w http.ResponseWriter, r *http.Request) {
	// TODO:
	// * look up the existing file to get its modtime and ensure it exists
	// * see if we have a cached file with the same modtime
	// ** if so, use it
	// ** otherwise, generate a preview
	// *** use graphicsmagick/ffmpeg to generate a preview thumbnail
	// *** store the new file to a mirroed path with the filename plus the modtime
	// * read the cached file and return its contents

	// cache preview thumbnails for a good while to lower load on this tiny
	// server, even if we are caching the preview thumbnails on-disk too.
	w.Header().Add("Cache-Control", "max-age=3600")
	w.Header().Add("Content-Type", "image/jpeg")
}

func main() {
	_, err := exec.LookPath("file")
	if err != nil {
		panic("The `file` executable could not be found; make sure it's installed!")
	}

	if len(os.Args) <= 1 {
		panic("A root directory argument is required")
	}

	ROOT = os.Args[1]

	fmt.Printf("Serving '%s'...\n", ROOT)

	r := mux.NewRouter()
	r.HandleFunc("/files/{path:.*}", getFileOrDirectory)
	r.HandleFunc("/thumbnails/{path:.*}", getThumbnail)

	http.ListenAndServe(":3000", r)
}
