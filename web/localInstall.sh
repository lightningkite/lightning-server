mkdir node_modules
mkdir 'node_modules/@lightningkite'
mkdir 'node_modules/@lightningkite/khrysalis-runtime'
mkdir 'node_modules/@lightningkite/android-xml-to-ios-xib'
mkdir 'node_modules/@lightningkite/rxjs-plus'
rsync -avz --delete-after --filter=':- .npmignore' --filter='+ .git' '../../khrysalis/' 'node_modules/@lightningkite/khrysalis-runtime/'
rsync -avz --delete-after --filter=':- .npmignore' --filter='+ .git' '../../android-xml-to-ios-xib/' 'node_modules/@lightningkite/android-xml-to-ios-xib/'
rsync -avz --delete-after --filter=':- .npmignore' --filter='+ .git' '../../rxjs-plus/' 'node_modules/@lightningkite/rxjs-plus/'
