package main

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"io/ioutil"
	"log"
	"mime"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"

	"github.com/gorilla/mux"
)

var ROOT = ""

type FileInfoJSON struct {
	Name        string `json:"name"`
	Size        int64  `json:"size"`
	ModifiedAt  string `json:"modified_at"`
	MIMEType    string `json:"mime_type"`
	IsCode      bool   `json:"is_code"`
	IsDirectory bool   `json:"is_directory"`
	IsHidden    bool   `json:"is_hidden"`
	IsLink      bool   `json:"is_link"`
}

type FileInfoJSONSorted []FileInfoJSON

func (f FileInfoJSONSorted) Len() int      { return len(f) }
func (f FileInfoJSONSorted) Swap(i, j int) { f[i], f[j] = f[j], f[i] }
func (f FileInfoJSONSorted) Less(i, j int) bool {
	fI := f[i]
	fJ := f[j]

	// directories always come before regular files
	if fI.IsDirectory && !fJ.IsDirectory {
		return true
	} else if !fI.IsDirectory && fJ.IsDirectory {
		return false
	}

	// split the strings into non-digit/digit sections
	nameI := strings.ToLower(fI.Name)
	nameJ := strings.ToLower(fJ.Name)
	segmentsI := partitionByDigitness(nameI)
	segmentsJ := partitionByDigitness(nameJ)
	minLen := len(segmentsI)
	if len(segmentsJ) < minLen {
		minLen = len(segmentsJ)
	}

	// compare each segment against its matching partner
	for i := 0; i < minLen; i++ {
		sI := segmentsI[i]
		sJ := segmentsJ[i]

		if sI != sJ {
			// get the first rune in each string so we can check whether it's a digit
			rI, _ := utf8.DecodeRuneInString(sI)
			rJ, _ := utf8.DecodeRuneInString(sJ)

			// if both chunks are digit-only, compare them numerically
			if unicode.IsDigit(rI) && unicode.IsDigit(rJ) {
				iI, errI := strconv.ParseUint(sI, 10, 64)
				iJ, errJ := strconv.ParseUint(sJ, 10, 64)

				// if we got an error for either string, compare lexicographically. this
				// isn't ideal, but it covers most cases since an unsigned long is no
				// less than 18 digits in length!
				if errI != nil || errJ != nil {
					return sort.StringsAreSorted([]string{sI, sJ})
				}

				// if they're not equal, return the comparison
				if iI != iJ {
					return iI < iJ
				}
			} else {
				// otherwise, do a lexicographic comparison
				return sort.StringsAreSorted([]string{sI, sJ})
			}
		}
	}

	// if all the segments we could directly compare are equal, do a lexicographic
	// comparison on the names.
	return sort.StringsAreSorted([]string{nameI, nameJ})
}

// given a string, returns an array of strings where each item is either
// digits-only or non-digits-only. if the string is either all-digit or
// no-digit, returns a single-item array of the input string.
func partitionByDigitness(s string) []string {
	result := []string{}
	cur := ""
	lastWasDigit := false

	for _, c := range s {
		if len(cur) == 0 {
			// only true on the first iteration
			cur = string(c)
			lastWasDigit = unicode.IsDigit(c)
		} else if unicode.IsDigit(c) != lastWasDigit {
			// we just hit an edge, so add our current section to the list and start
			// a new one with this character.
			result = append(result, cur)
			cur = string(c)
			lastWasDigit = unicode.IsDigit(c)
		} else {
			cur = cur + string(c)
		}
	}

	result = append(result, cur)

	return result
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

// given a file name, returns a MIME type based on its extension
func getMIMEType(filePath string) string {
	dotIndex := strings.LastIndex(filePath, ".")
	if dotIndex < 0 {
		return ""
	}

	return mime.TypeByExtension(filePath[dotIndex:])
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
	rawPath, err := url.QueryUnescape(mux.Vars(r)["path"])
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	normalizedPath, err := normalizePathUnderRoot(ROOT, rawPath)
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}

	// stat the file so we can return its info
	fileInfo, err := os.Stat(normalizedPath)
	if err != nil {
		// don't report the raw error in case we leak server directory information
		http.Error(w, "Could not find "+rawPath, 404)
		return
	}

	mimeType := getMIMEType(normalizedPath)

	writeJSONResponse(w, FileInfoJSON{
		fileInfo.Name(),
		fileInfo.Size(),
		fileInfo.ModTime().Format("2006-01-02T15:04:05Z"), // ISO 8601
		mimeType,
		!fileInfo.IsDir() && isSourceCode(fileInfo.Name()),
		fileInfo.IsDir(),
		strings.HasPrefix(fileInfo.Name(), "."),
		fileInfo.Mode()&os.ModeSymlink == os.ModeSymlink,
	})
}

func download(w http.ResponseWriter, r *http.Request) {
	// make sure our path is valid
	rawPath, err := url.QueryUnescape(mux.Vars(r)["path"])
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
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
		http.Error(w, "Could not find "+rawPath, 404)
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
	rawPath, err := url.QueryUnescape(mux.Vars(r)["path"])
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	normalizedPath, err := normalizePathUnderRoot(ROOT, rawPath)
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}

	children, err := ioutil.ReadDir(normalizedPath)
	if err != nil {
		// don't report the raw error in case we leak server directory information
		http.Error(w, "Could not find "+rawPath, 404)
		return
	}

	// list the directory to a JSON response
	var files []FileInfoJSON
	for _, file := range children {
		fileName := file.Name()
		files = append(files, FileInfoJSON{
			fileName,
			file.Size(),
			file.ModTime().Format("2006-01-02T15:04:05Z"), // ISO 8601
			getMIMEType(fileName),
			!file.IsDir() && isSourceCode(fileName),
			file.IsDir(),
			strings.HasPrefix(fileName, "."), // hidden?
			file.Mode()&os.ModeSymlink == os.ModeSymlink,
		})
	}

	// sort the files by our special sort order
	sort.Sort(FileInfoJSONSorted(files))

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

// generates a thumbnail file given a path, or returns an error if no thumbnail
// could be generated.
func getThumbnail(w http.ResponseWriter, r *http.Request) {
	rawPath, err := url.QueryUnescape(mux.Vars(r)["path"])
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	normalizedPath, err := normalizePathUnderRoot(ROOT, rawPath)
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}

	// ensure the file exists
	_, err = os.Stat(normalizedPath)
	if err != nil {
		// don't report the raw error in case we leak server directory information
		http.Error(w, "Could not find "+rawPath, 404)
		return
	}

	// tell the browser to cache this response for a good while to lower load
	w.Header().Add("Cache-Control", "max-age=3600")

	// get the file's MIME type so we can see what it is
	mimeType := getMIMEType(normalizedPath)

	var cmd *exec.Cmd
	size := "256"
	if mimeType == "image/svg+xml" {
		// simply return the image as-is if it's an SVG image
		http.ServeFile(w, r, normalizedPath)
		return
	} else if strings.Index(mimeType, "image") == 0 {
		// generate a JPEG thumbnail for the image using GraphicsMagick
		cmd = exec.Command(
			"gm", "convert",
			"-size", size+"x"+size,
			normalizedPath,
			"-geometry", size+"x"+size+"^",
			"+profile", "\"*\"",
			"jpeg:-",
		)
	} else if strings.Index(mimeType, "video") == 0 {
		// generate a JPEG thumbnail for the image using ffmpeg
		cmd = exec.Command(
			"ffmpeg",
			"-i", normalizedPath,
			"-vf", "thumbnail,scale=-1:"+size,
			"-frames:v", "1",
			"-f", "mjpeg",
			"-",
		)
	} else {
		// HTTP 415 - Unsupported Media Type
		http.Error(w, "Unsupported file type: "+mimeType, 415)
		return
	}

	// run the command we created above and get its JPEG output
	var out bytes.Buffer
	cmd.Stdout = &out
	err = cmd.Run()
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}

	w.Header().Add("Content-Type", "image/jpeg")
	w.Write(out.Bytes())
}

func main() {
	// ensure we have all the binaries we need
	requiredBinaries := []string{"gm", "ffmpeg"}
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
