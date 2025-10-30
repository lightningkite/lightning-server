#!/bin/bash

# Configuration
# The directory containing the .kt files.
# Use '.' for the current directory, or specify a path.
TARGET_DIR=".."

# The file to store the list of already processed files (the blacklist).
BLACKLIST_FILE="review-loop-complete.txt"

# The command you want to run on each file.
# The placeholder '{}' will be replaced by the filename.
# Example: 'ktlint {} --fix' or 'some_other_command {}'
# IMPORTANT: Adjust this command to your needs.
PROCESS_COMMAND="echo 'Processing file: {}'"

# --- Function to make path relative to TARGET_DIR (macOS compatible) ---
get_relative_path() {
    local file_path="$1"
    local target_dir="$2"

    # Ensure both paths are absolute for comparison
    local abs_file_path
    abs_file_path=$(cd "$(dirname "$file_path")"; pwd)/$(basename "$file_path")
    local abs_target_dir
    abs_target_dir=$(cd "$target_dir"; pwd)

    # Use Bash parameter expansion to remove the absolute target directory prefix.
    # The '//' ensures all occurrences are removed, though only one should exist at the start.
    local relative_path="${abs_file_path#$abs_target_dir/}"

    # If the paths are the same (e.g., target_dir is '.'), ensure the relative path is correct
    if [ "$abs_file_path" = "$abs_target_dir" ]; then
        echo "."
    else
        echo "$relative_path"
    fi
}
# --- End of Function ---

# --- Start of Script ---

echo "Starting Kotlin file processing..."
echo "Target Directory: ${TARGET_DIR}"

# 1. Create the blacklist file if it doesn't exist
touch "${BLACKLIST_FILE}"

# 2. Find all .kt files and iterate over them
# - find "${TARGET_DIR}" -type f -name "*.kt": Finds all .kt files recursively.
# - while IFS= read -r file: Reads each filename, handling spaces and special characters.
find "${TARGET_DIR}" -type f -name "*.kt" | while IFS= read -r file; do
    # Get the relative path for consistent blacklisting
    relative_file_path=$(get_relative_path "${file}" "${TARGET_DIR}")

    if [[ "${relative_file_path}" =~ /(test[^/]*)/ ]]; then
        echo "🚫 EXCLUDED: ${relative_file_path} (File is inside a 'test*' folder)"
        continue # Skip to the next file in the loop
    fi
    if [[ "${relative_file_path}" =~ /(generated[^/]*)/ ]]; then
        echo "🚫 EXCLUDED: ${relative_file_path} (File is inside a 'test*' folder)"
        continue # Skip to the next file in the loop
    fi

    # 3. Check if the file is in the blacklist
    if grep -Fxq "${relative_file_path}" "${BLACKLIST_FILE}"; then
        echo "✅ SKIPPED: ${relative_file_path} (Already processed)"
    else
        echo -e "\n🛠 PROCESSING: ${relative_file_path}"

        # 4. Run the desired command
        # Replace the placeholder '{}' with the actual file path
        # eval is used here to correctly execute the command with the substituted path
        eval "${PROCESS_COMMAND//\{\}/\047${file}\047}"

        # Check the exit status of the command
        if [ $? -eq 0 ]; then
            echo "✨ SUCCESS: Command finished for ${relative_file_path}"

            # 5. Add the file to the blacklist
            echo "${relative_file_path}" >> "${BLACKLIST_FILE}"
            echo "  -> Added to blacklist."
        else
            echo "❌ FAILED: Command returned an error for ${relative_file_path}. NOT adding to blacklist."
        fi
    fi
done

echo -e "\nProcessing complete."
echo "Blacklist file updated: ${BLACKLIST_FILE}"
echo "To reset the process, you can delete the file: rm ${BLACKLIST_FILE}"

# --- End of Script ---