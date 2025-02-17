// Copyright 2022-2023 Buf Technologies, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package build.buf.intellij.index

import build.buf.intellij.config.BufConfig
import build.buf.intellij.model.BufModuleCoordinates
import com.intellij.openapi.project.Project
import com.intellij.util.indexing.*
import com.intellij.util.io.IOUtil
import com.intellij.util.io.KeyDescriptor
import org.jetbrains.yaml.psi.*
import java.io.DataInput
import java.io.DataOutput

class BufModuleIndex : ScalarIndexExtension<BufModuleCoordinates>() {
    companion object {
        private val INDEX_ID = ID.create<BufModuleCoordinates, Void>("BufModuleIndex")

        fun getAllProjectModules(project: Project): Collection<BufModuleCoordinates> {
            return FileBasedIndex.getInstance().getAllKeys(INDEX_ID, project)
        }
    }

    override fun getName(): ID<BufModuleCoordinates, Void> = INDEX_ID

    override fun dependsOnFileContent(): Boolean = true

    override fun getVersion(): Int = 2

    override fun getInputFilter(): FileBasedIndex.InputFilter {
        return FileBasedIndex.InputFilter { file ->
            file.name == BufConfig.BUF_LOCK
        }
    }

    override fun getKeyDescriptor(): KeyDescriptor<BufModuleCoordinates> = BufModuleIndexEntryKeyDescriptor

    override fun getIndexer(): DataIndexer<BufModuleCoordinates, Void, FileContent> {
        return DataIndexer { inputData ->
            val yamlFile = inputData.psiFile as? YAMLFile ?: return@DataIndexer emptyMap()
            val lockFileURL = yamlFile.virtualFile?.url ?: return@DataIndexer emptyMap()
            val result = linkedSetOf<BufModuleCoordinates>()
            yamlFile.accept(object : YamlRecursivePsiElementVisitor() {
                override fun visitKeyValue(keyValue: YAMLKeyValue) {
                    if (keyValue.key?.textMatches("deps") == true) {
                        val yamlDeps = (keyValue.value as? YAMLSequence)?.items ?: emptyList()
                        result.addAll(yamlDeps.mapNotNull { findModuleDep(lockFileURL, it) })
                    }
                }
            })
            return@DataIndexer result.associateWith { null }
        }
    }

    private fun findModuleDep(lockFileURL: String, item: YAMLSequenceItem): BufModuleCoordinates? {
        val bufModuleItem = item.keysValues.associate {
            it.keyText to it.valueText
        }
        return BufModuleCoordinates(
            lockFileURL,
            remote = bufModuleItem["remote"] ?: return null,
            owner = bufModuleItem["owner"] ?: return null,
            repository = bufModuleItem["repository"] ?: return null,
            commit = bufModuleItem["commit"] ?: return null,
        )
    }
}

object BufModuleIndexEntryKeyDescriptor : KeyDescriptor<BufModuleCoordinates> {
    override fun getHashCode(value: BufModuleCoordinates): Int = value.hashCode()

    override fun isEqual(a: BufModuleCoordinates, b: BufModuleCoordinates): Boolean = a == b

    override fun save(out: DataOutput, value: BufModuleCoordinates) {
        IOUtil.writeStringList(out, listOf(value.lockFileURL, value.remote, value.owner, value.repository, value.commit))
    }

    override fun read(input: DataInput): BufModuleCoordinates {
        val deserializedList = IOUtil.readStringList(input)
        return BufModuleCoordinates(
            lockFileURL = deserializedList[0],
            remote = deserializedList[1],
            owner = deserializedList[2],
            repository = deserializedList[3],
            commit = deserializedList[4],
        )
    }
}
