package main

import (
	"archive/zip"
	"encoding/json"
	"fmt"
	"io"
	"io/ioutil"
	"log"
	"net/http"
	"os"
	"os/exec"
	"path"
	"path/filepath"
	"strings"

	"github.com/gorilla/mux"
)

var ROOT = ""

type FileInfoJSON struct {
	Name        string `json:"name"`
	Size        int64  `json:"size"`
	ModifiedAt  string `json:"modified_at"`
	MIMEType    string `json:"mime_type"`
	IsDirectory bool   `json:"is_directory"`
	IsHidden    bool   `json:"is_hidden"`
	IsLink      bool   `json:"is_link"`
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

func writeJSONResponse(w http.ResponseWriter, data interface{}) {
	w.Header().Add("Content-Type", "application/json")
	w.Header().Add("Cache-Control", "no-cache")

	json, err := json.Marshal(data)
	if err != nil {
		http.Error(w, "Failed to generate JSON response", 500)
		return
	}
	w.Write(json)
}

// given a path, returns a map of child name to MIME type
func getChildMIMETypes(parentPath string) map[string]string {
	result := make(map[string]string)

	// get all the children in the given directory
	children, err := filepath.Glob(path.Join(parentPath, "*"))
	if err != nil {
		return result
	}

	args := []string{"--mime-type", "--dereference", "--preserve-date"}
	args = append(args, children...)

	// call `file` for a newline-delimited list of "filename: MIME-type" pairs
	fileOutput, err := exec.Command("file", args...).Output()

	if err != nil {
		return result
	}

	for _, line := range strings.Split(string(fileOutput), "\n") {
		fileName, mimeType, err := parseMIMEType(line)
		if err == nil {
			result[fileName] = mimeType
		}
	}

	return result
}

func getMIMEType(filePath string) string {
	fileOutput, err := exec.Command(
		"file",
		"--mime-type",
		// "--dereference",
		"--preserve-date",
		"--brief",
		filePath,
	).Output()

	if err != nil {
		return ""
	}

	return strings.TrimSpace(string(fileOutput))
}

// given a root and a relative child path, returns the normalized, absolute path
// of the child. if the path is not a child of the root or is otherwise invalid,
// returns an error.
func normalizePathUnderRoot(root, child string) (string, error) {
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

// this returns the info for the specified files _or_ directory, not just files
func getInfo(w http.ResponseWriter, r *http.Request) {
	// make sure our path is valid
	rawPath := mux.Vars(r)["path"]
	normalizedPath, err := normalizePathUnderRoot(ROOT, rawPath)
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}

	// stat the file so we can return its info
	fileInfo, err := os.Stat(normalizedPath)
	if err != nil {
		// don't report the raw error in case we leak server directory information
		http.Error(w, "", 404)
		return
	}

	mimeType := getMIMEType(normalizedPath)

	writeJSONResponse(w, FileInfoJSON{
		fileInfo.Name(),
		fileInfo.Size(),
		fileInfo.ModTime().Format("2006-01-02T15:04:05Z"), // ISO 8601
		mimeType,
		fileInfo.IsDir(),
		strings.HasPrefix(fileInfo.Name(), "."),
		fileInfo.Mode()&os.ModeSymlink == os.ModeSymlink,
	})
}

func download(w http.ResponseWriter, r *http.Request) {
	// make sure our path is valid
	rawPath := mux.Vars(r)["path"]
	normalizedPath, err := normalizePathUnderRoot(ROOT, rawPath)
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}

	// stat the file so we can set appropriate response headers, and so we can
	// ensure it's a regular file and not a directory.
	file, err := os.Stat(normalizedPath)
	if err != nil {
		// don't report the raw error in case we leak server directory information
		http.Error(w, "", 404)
		return
	}

	// return different responses depending on file type
	if file.IsDir() {
		downloadDirectory(w, r, normalizedPath)
	} else {
		downloadFile(w, r, normalizedPath, file)
	}
}

func downloadFile(w http.ResponseWriter, r *http.Request, filePath string, file os.FileInfo) {
	mimeType := getMIMEType(filePath)
	w.Header().Add("Content-Type", mimeType)
	w.Header().Add("Content-Disposition", file.Name())
	w.Header().Add("Cache-Control", "no-cache")

	http.ServeFile(w, r, filePath)
}

func getDirectory(w http.ResponseWriter, r *http.Request) {
	// ensure the directory actually exists
	rawPath := mux.Vars(r)["path"]
	normalizedPath, err := normalizePathUnderRoot(ROOT, rawPath)
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}

	children, err := ioutil.ReadDir(normalizedPath)
	if err != nil {
		// don't report the raw error in case we leak server directory information
		http.Error(w, "", 404)
		return
	}

	// get a map of all the MIME types for the directory
	mimeTypes := getChildMIMETypes(normalizedPath)

	// list the directory to a JSON response
	var files []FileInfoJSON
	for _, file := range children {
		fileName := file.Name()
		mimeType := mimeTypes[path.Join(normalizedPath, fileName)]

		files = append(files, FileInfoJSON{
			fileName,
			file.Size(),
			file.ModTime().Format("2006-01-02T15:04:05Z"), // ISO 8601
			mimeType,
			file.IsDir(),
			strings.HasPrefix(fileName, "."), // hidden?
			file.Mode()&os.ModeSymlink == os.ModeSymlink,
		})
	}

	writeJSONResponse(w, files)
}

// zip up a directory and write it to the response stream
func downloadDirectory(w http.ResponseWriter, r *http.Request, dirPath string) {
	// give the file a nice name, but replace the root directory name with
	// something generic.
	var downloadName string
	if dirPath == ROOT {
		downloadName = "files.zip"
	} else {
		downloadName = path.Base(dirPath) + ".zip"
	}

	w.Header().Add("Content-Type", "application/zip")
	w.Header().Add("Content-Disposition", downloadName)
	w.Header().Add("Cache-Control", "no-cache")

	z := zip.NewWriter(w)
	defer z.Close()

	// walk the directory and add each file to the zip file, giving up (returning
	// an error) if we encounter an error anywhere along the line.
	filepath.Walk(dirPath, func(fullFilePath string, file os.FileInfo, err error) error {
		if err != nil {
			// don't say what failed since doing so might leak the full path
			http.Error(w, "Failed to generate archive", 500)
			return err
		}

		// use the relative file path so we don't accidentally leak the full path
		// anywhere. we only use the full path to read the file from disk. we know
		// it's relative so we can ignore the error.
		filePath, _ := filepath.Rel(dirPath, fullFilePath)

		// build a header we can use to generate a ZIP archive entry
		header, err := zip.FileInfoHeader(file)
		if err != nil {
			http.Error(w, fmt.Sprintf("Failed to generate archive header for %s", filePath), 500)
			return err
		}

		// ensure the name is set to relative path within this directory so we'll
		// preserve the directory's structure within the archive.
		header.Name = filePath

		// add a directory entry for true directories so they'll show up even if
		// they have no children. adding a trailing `/` does this for us,
		// apparently.
		fileIsSymlink := file.Mode()&os.ModeSymlink == os.ModeSymlink
		if file.IsDir() && !fileIsSymlink {
			header.Name += "/"
		}

		// generate an archive entry for this file/directory/symlink
		zf, err := z.CreateHeader(header)
		if err != nil {
			http.Error(w, fmt.Sprintf("Failed to add %s to archive", filePath), 500)
			return err
		}

		// if the file is a symlink, preserve it as such
		if fileIsSymlink {
			// according to the ZIP format, symlinks must have the namesake file mode
			// with sole body content of the string path of the link's destination.
			dest, err := os.Readlink(fullFilePath)
			if err != nil {
				http.Error(w, fmt.Sprintf("Failed to resolve %s", filePath), 500)
				return err
			}

			zf.Write([]byte(dest))
		} else if file.IsDir() {
			// NOTE: do nothing since all we have to do for directories is create
			// their header entry, which has already been done.
		} else {
			// open the file for reading
			f, err := os.Open(fullFilePath)
			if err != nil {
				http.Error(w, fmt.Sprintf("Failed to read %s", filePath), 500)
				return err
			}
			defer f.Close()

			// write the file contents to the archive
			written, err := io.Copy(zf, f)
			if err != nil {
				http.Error(w, fmt.Sprintf("Failed to write %s to archive after %d bytes", filePath, written), 500)
				return err
			}
		}

		// flush what we've written so far to the client so the download will be as
		// incremental as possible. doing flushes after every file also ensures that
		// our memory usage doesn't balloon to the entire size of the zipped
		// directory, just the size of one file (which is better than nothing...).
		err = z.Flush()
		if err != nil {
			http.Error(w, fmt.Sprintf("Failed to flush data for %s", filePath), 500)
			return err
		}

		return nil
	})
}

// retrieves/caches/updates a thumbnail file given a path, or returns an error
// if no thumbnail could be geneated.
func getThumbnail(w http.ResponseWriter, r *http.Request) {
	// TODO:
	// * look up the parent file to get its modtime and ensure it exists
	// * see if we have a cached file with the same modtime
	// ** if so, use it
	// ** otherwise, generate a preview
	// *** use graphicsmagick/ffmpeg to generate a preview thumbnail
	// *** store the thumbnail to a mirrored path with the filename plus the modtime
	// * read the cached file and return its contents

	// cache preview thumbnails for a good while to lower load on this tiny
	// server, even if we are caching the preview thumbnails on-disk too.
	w.Header().Add("Cache-Control", "max-age=3600")
	w.Header().Add("Content-Type", "image/jpeg")
}

func main() {
	// ensure we have all the binaries we need
	requiredBinaries := []string{"file"}
	for _, binary := range requiredBinaries {
		if _, err := exec.LookPath(binary); err != nil {
			log.Panicf("'%s' must be installed and in the PATH\n", binary)
		}
	}

	if len(os.Args) <= 1 {
		panic("A root directory argument is required")
	}

	ROOT = path.Clean(os.Args[1])

	router := mux.NewRouter()

	// /files
	// anything with a trailing `/` indicates a directory; anything that ends
	// without a trailing slash indicates a file.
	filesJSON := router.Headers("Content-Type", "application/json").Subrouter()
	filesJSON.HandleFunc("/files/{path:.*[^/]$}", getInfo).
		Headers("Content-Type", "application/json").
		Methods("GET")
	filesJSON.HandleFunc("/files{path:.*}/", getDirectory).
		Headers("Content-Type", "application/json").
		Methods("GET")

	router.HandleFunc("/files/{path:.*}", download).
		Methods("GET")

	// /thumbnails
	router.HandleFunc("/thumbnails/{path:.*[^/]$}", getThumbnail).
		Methods("GET")

	// /resources (static files)
	router.HandleFunc("/resources/{path:.*}", func(w http.ResponseWriter, r *http.Request) {
		http.ServeFile(w, r, "ui/resources/"+mux.Vars(r)["path"])
	})

	// /home (UI)
	router.HandleFunc("/home{path:.*}", func(w http.ResponseWriter, r *http.Request) {
		http.ServeFile(w, r, "ui/resources/index.html")
	})

	addr := "127.0.0.1:3000"
	fmt.Printf("Serving %s to %s...\n", ROOT, addr)
	http.ListenAndServe(addr, router)
}
