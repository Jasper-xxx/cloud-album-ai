<template>
    <div class="record-container">
        <div class="content-header">
            <span>操作记录</span>
            <div class="header-actions">
                <el-button 
                    type="danger" 
                    :disabled="selectedRecords.length === 0"
                    @click="handleDelete"
                    size="small"
                >
                    批量删除
                </el-button>
                <el-button 
                    type="danger" 
                    @click="handleClearAll"
                    size="small"
                    style="margin-left: 10px"
                >
                    清空全部
                </el-button>
            </div>
        </div>
        <el-divider />
        <div class="record-table">
            <!-- 数据表格 -->
            <el-table 
                :data="records" 
                style="width: calc(100% - 40px)  ;margin: 20px;"
                @selection-change="handleSelectionChange"
            >
                <el-table-column type="selection" width="55"></el-table-column>
                <el-table-column prop="operationTime" label="操作时间">
                    <template #default="scope">
                        <div v-html="formatDate(scope.row.operationTime)"></div>
                    </template>
                </el-table-column>
                <el-table-column prop="operation" label="操作" width="450" />
                <el-table-column prop="number" label="数量" />
                <el-table-column prop="ipv4" label="IPv4 地址" width="300"/>
                <el-table-column prop="userId" label="用户ID" />
            </el-table>
        </div>
        <!-- 分页组件 -->
        <el-pagination class="pagination" @size-change="handleSizeChange" @current-change="handleCurrentChange"
            total-text="记录总数" prev-text="上一页" next-text="下一页" :current-page="currentPage"
            :page-sizes="[5, 10,20, 30,50]" :page-size="pageSize" layout="total, sizes, prev, pager, next, jumper"
            :total="total">
        </el-pagination>

        <!-- 人机验证对话框 -->
        <el-dialog
            v-model="verifyDialogVisible"
            title="确认删除"
            width="420px"
            :close-on-click-modal="!deleting"
            :close-on-press-escape="!deleting"
            @opened="refreshCaptcha"
            @closed="resetVerifyForm"
        >
            <div class="verify-panel">
                <el-alert
                    :title="verifyDialogTitle"
                    type="warning"
                    :closable="false"
                    show-icon
                />
                <p class="verify-tip">{{ verifyDialogMessage }}</p>
                <el-form
                    ref="verifyFormRef"
                    :model="verifyForm"
                    :rules="verifyRules"
                    label-position="top"
                    @submit.prevent
                >
                    <el-form-item label="图形验证码" prop="captchaInput">
                        <div class="captcha-input">
                            <el-input
                                v-model="verifyForm.captchaInput"
                                placeholder="请输入图形验证码"
                                :prefix-icon="Key"
                                autocomplete="off"
                                @keyup.enter="confirmDelete"
                            />
                            <CaptchaVerify ref="captchaRef" @change="handleCaptchaChange" />
                        </div>
                    </el-form-item>
                </el-form>
            </div>
            <template #footer>
                <span class="dialog-footer">
                    <el-button :disabled="deleting" @click="verifyDialogVisible = false">取消</el-button>
                    <el-button
                        type="danger"
                        :loading="deleting"
                        :disabled="!verifyForm.captchaInput"
                        @click="confirmDelete"
                    >
                        确认删除
                    </el-button>
                </span>
            </template>
        </el-dialog>
    </div>
</template>

<script lang="ts" setup>
import { computed, nextTick, onMounted, reactive, ref } from 'vue';
import { Key } from '@element-plus/icons-vue';
import type { FormInstance, FormRules } from 'element-plus';
import { selectAll, deleteRecords, clearAllRecords } from '@/api/record/record';
import { ElMessage } from 'element-plus';
import CaptchaVerify from '@/components/auth/CaptchaVerify.vue';
// 定义记录接口
interface Record {
    id: number; // 添加ID字段
    operationTime: string; // 根据实际后端返回类型调整
    operation: string;
    number: number;
    ipv4: string;
    userId: number;
}
onMounted(() => {
    loadRecord();

});
const loadRecord =  async () => {
    const res = await selectAll({
        current: currentPage.value,
        size: pageSize.value,
    });
    if (res.code != 200) {
        return
    }
    total.value = res.data.total;

    records.value = res.data.records;

}
// 示例数据，实际项目中应通过 API 获取
const records = ref<Record[]>([
    {
        id: 1,
        operationTime: '2023-10-01T10:15:30Z',
        operation: '上传图片',
        number: 5,
        ipv4: '192.168.1.1',
        userId: 1001,
    },
    // ...更多记录
]);
const total = ref(0)
const currentPage = ref(1); // 当前页码
const pageSize = ref(10); // 每页显示的记录数

// 选中的记录
const selectedRecords = ref<Record[]>([]);
// 人机验证对话框
const verifyDialogVisible = ref(false);
// 删除类型：batch 或 all
const deleteType = ref<'batch' | 'all'>('batch');
const deleting = ref(false);
const verifyFormRef = ref<FormInstance>();
const captchaRef = ref<InstanceType<typeof CaptchaVerify>>();
const captchaCode = ref('');
const verifyForm = reactive({
    captchaInput: '',
});

const verifyRules = reactive<FormRules>({
    captchaInput: [
        { required: true, message: '请输入图形验证码', trigger: 'blur' },
        {
            validator: (_rule, value, callback) => {
                if (!value) {
                    callback(new Error('请输入图形验证码'));
                    return;
                }
                if (!captchaCode.value) {
                    callback(new Error('验证码加载中，请稍后'));
                    return;
                }
                if (String(value).trim().toLowerCase() !== captchaCode.value.toLowerCase()) {
                    callback(new Error('图形验证码不正确'));
                    return;
                }
                callback();
            },
            trigger: 'blur',
        },
    ],
});

const verifyDialogTitle = computed(() => deleteType.value === 'all' ? '清空操作记录' : '删除操作记录');
const verifyDialogMessage = computed(() => {
    if (deleteType.value === 'all') {
        return `将清空全部 ${total.value} 条操作记录，此操作不可恢复，请先完成人机验证。`;
    }
    return `将删除已选中的 ${selectedRecords.value.length} 条操作记录，此操作不可恢复，请先完成人机验证。`;
});

// 处理记录选择
const handleSelectionChange = (selection: Record[]) => {
    selectedRecords.value = selection;
};

// 处理批量删除
const handleDelete = () => {
    if (selectedRecords.value.length === 0) return;
    deleteType.value = 'batch';
    verifyDialogVisible.value = true;
};

// 处理清空全部
const handleClearAll = () => {
    deleteType.value = 'all';
    verifyDialogVisible.value = true;
};

const handleCaptchaChange = (value: string) => {
    captchaCode.value = value;
};

const refreshCaptcha = async () => {
    verifyForm.captchaInput = '';
    await nextTick();
    captchaRef.value?.refreshCaptcha();
    verifyFormRef.value?.clearValidate('captchaInput');
};

const resetVerifyForm = () => {
    verifyForm.captchaInput = '';
    captchaCode.value = '';
    verifyFormRef.value?.clearValidate();
};

// 确认删除
const confirmDelete = async () => {
    if (deleting.value) return;

    try {
        await verifyFormRef.value?.validate();
    } catch {
        ElMessage.error('人机验证未通过，请重新输入验证码');
        await refreshCaptcha();
        return;
    }

    deleting.value = true;
    try {
        if (deleteType.value === 'batch') {
            const recordIds = selectedRecords.value.map(item => item.id);
            const res = await deleteRecords(recordIds);
            if (res.code === 200) {
                ElMessage.success(res.message || '删除成功');
                selectedRecords.value = [];
                loadRecord(); // 重新加载记录
            } else {
                ElMessage.error(res.message || '删除失败');
                await refreshCaptcha();
                return;
            }
        } else {
            const res = await clearAllRecords();
            if (res.code === 200) {
                ElMessage.success(res.message || '清空成功');
                loadRecord(); // 重新加载记录
            } else {
                ElMessage.error(res.message || '清空失败');
                await refreshCaptcha();
                return;
            }
        }
        verifyDialogVisible.value = false;
    } catch (error) {
        ElMessage.error('操作失败');
        await refreshCaptcha();
    } finally {
        deleting.value = false;
    }
};

// 格式化日期的方法
const formatDate = (dateStr: string): string => {
    const date = new Date(dateStr);
    // 分别获取日期和时间部分
    const datePart = date.toLocaleDateString(); // 年-月-日
    const timePart = date.toLocaleTimeString(); // 时:分:秒
    // 使用换行符将日期和时间分开显示
    return `${datePart}<br>${timePart}`;
};

// 处理每页大小变化
const handleSizeChange = (newSize: number) => {
    
    pageSize.value = newSize;
    currentPage.value = 1; // 重置到第一页
    loadRecord();
};

// 处理当前页码变化
const handleCurrentChange = (newPage: number) => {
  
    currentPage.value = newPage;
    loadRecord();
};
</script>

<style scoped>
.record-container {
    width: calc(100% - 40px);
    height: calc(100% - 90px);

}

.record-table {
    width: 100%;
    height: calc(100% - 80px);
  
    overflow: auto;
    position: relative;
}

.pagination {
    margin-left: 30px;
    margin-top: 30px;
    text-align: right;
}

.content-header {
    font-size: 20px;
    color: #333;
    letter-spacing: 0;
    padding: 20px;
    display: flex;
    justify-content: space-between;
    align-items: center;
}

.header-actions {
    display: flex;
    align-items: center;
}

.verify-panel {
    display: flex;
    flex-direction: column;
    gap: 14px;
}

.verify-tip {
    margin: 0;
    color: #606266;
    line-height: 1.6;
}

.captcha-input {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    align-items: center;
    width: 100%;
}

.captcha-input .el-input {
    flex: 1 1 160px;
    min-width: 140px;
}

/* 自定义ElementUI分割线 */
.el-divider--horizontal {
    margin-top: 0px;
    margin-bottom: 0px;
}

@media (max-width: 992px) {
    .record-container {
        width: 100%;
        height: auto;
    }

    .record-table :deep(.el-table) {
        width: 100% !important;
        margin: 12px 0 !important;
    }

    .pagination {
        margin-left: 0;
        margin-top: 12px;
        text-align: center;
        padding: 0 8px;
        box-sizing: border-box;
    }
}
</style>
