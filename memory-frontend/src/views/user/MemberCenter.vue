<template>
    <div id="member">
        <div class="member-container">

            <!-- 会员状态展示 -->
            <div class="member-status">
                <el-card shadow="hover">
                    <div class="current-VIP">


                        <i-tdesign:user-vip class="VIP-icon" :style="{ color: isMember ? '#ffd700' : '#999' }" />


                        <span class="status-text">
                            {{ isMember ? `尊贵的${currentLevel}会员` : '您尚未开通会员' }}
                        </span>
                        <span v-if="isMember" class="expire-time">有效期至：{{ expireDate }}</span>
                    </div>
                </el-card>
            </div>

            <!-- 会员套餐选择 -->
            <div class="package-select">
                <el-radio-group v-model="selectedDuration" class="duration-switch">
                    <el-radio-button value="month">按月订阅</el-radio-button>
                    <el-radio-button value="year">按年订阅（立省30%）</el-radio-button>
                </el-radio-group>

                <div class="package-list">
                    <el-card v-for="(pkg, index) in packages" :key="index"
                        :class="['package-card', { 'active': selectedPackage === pkg.level }]"
                        @click="selectedPackage = pkg.level">
                        <div class="package-header">
                            <h3>{{ pkg.level }}会员</h3>
                            <el-tag v-if="pkg.recommend" type="danger" effect="dark">推荐</el-tag>
                        </div>

                        <div class="price-section">
                            <span class="currency">¥</span>
                            <span class="amount">{{ selectedDuration === 'month' ? pkg.monthPrice : pkg.yearPrice
                                }}</span>
                            <span class="duration">/{{ durationMap[selectedDuration] }}</span>
                        </div>

                        <div class="privilege-list">
                            <div v-for="(item, idx) in pkg.privileges" :key="idx" class="privilege-item">
                                <el-icon>
                                    <Check />
                                </el-icon>
                                <span>{{ item }}</span>
                            </div>
                        </div>

                        <el-button style="position: absolute;bottom: 10px;right:20px" type="primary"
                            :class="{ 'is-member': isMember && currentLevel === pkg.level }"
                            @click.stop="handleUpgrade(pkg)">
                            {{ getButtonText(pkg) }}
                        </el-button>
                    </el-card>
                </div>
            </div>
        </div>
    </div>
</template>

<script lang="ts" setup>
import { ref, computed, onMounted, watch } from 'vue'
import { Check } from '@element-plus/icons-vue'
import { getUserInfo, handleUpdateUserStatus, handleAddUserStorage } from '@/api/user/index'
import { storage } from '@/utils/storage'
import $bus from '@/utils/bus.ts'

interface MemberPackage {
    level: 'VIP' | 'SVIP'
    monthPrice: number
    yearPrice: number
    privileges: string[]
    recommend?: boolean
}

const userInfo = ref(<API.UserInfo>{
    userId: 1000000001,
    userName: "鹏",
    account: "xzp",
    email: "1014537454@qq.com",
    createTime: "2025-02-20T15:18:26",
    updateTime: "2025-03-04T22:31:21",
    profile: "您好",
    avatarUrl: "",
    totalSpace: 21474836480,
    usedSpace: 32988616,
    accountStatus: "normal",
    membershipDays: 0
})

const selectUserInfo = async () => {
    const token = localStorage.getItem('token')
    if (token) {
        const res = await getUserInfo()
        if (res.code == 200) {
            userInfo.value = res.data
        }
    }
}

onMounted(() => {
    const Info = storage.get<API.UserInfo>('USER_INFO')
    if (Info) {
        userInfo.value = Info
    } else {
        selectUserInfo()
    }
})

// 会员套餐数据
const packages = ref<MemberPackage[]>([
    {
        level: 'VIP',
        monthPrice: 30,
        yearPrice: 300,
        privileges: [
            '20GB 云存储空间',
            '高清图片上传',
            '专属客服支持',
            '优先审核通道'
        ]
    },
    {
        level: 'SVIP',
        monthPrice: 50,
        yearPrice: 500,
        privileges: [
            '50GB 云存储空间',
            '4K超清上传',
            'VIP专属模板',
            '双重备份保障',
            '24小时专属客服'
        ],
        recommend: true
    }
])

const durationMap = { month: '月', year: '年' } as const
const selectedDuration = ref<'month' | 'year'>('month')

// 计算属性：是否已是会员、当前等级、到期日
const isMember = computed(() => userInfo.value.accountStatus !== 'normal')
const currentLevel = computed(() => userInfo.value.accountStatus.toUpperCase())

const expireDate = computed(() => {
    if (!isMember.value || !userInfo.value.membershipDays) return ''
    const date = new Date()
    date.setDate(date.getDate() + userInfo.value.membershipDays)
    return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
})

const selectedPackage = ref<MemberPackage['level']>('VIP')
watch(
    () => userInfo.value.accountStatus,
    (status) => {
        selectedPackage.value = status === 'svip' ? 'SVIP' : 'VIP'
    },
    { immediate: true }
)

// 加载中防重复点击标志
const upgrading = ref(false)

const handleUpgrade = async (pkg: MemberPackage) => {
    if (userInfo.value.accountStatus === pkg.level.toLowerCase()) return
    if (upgrading.value) return

    try {
        await ElMessageBox.confirm(
            `确认${userInfo.value.accountStatus !== 'normal' ? '切换' : '开通'}${pkg.level}会员？\n` +
            `套餐权益：${pkg.level === 'SVIP' ? '50GB存储 / 4K超清上传' : '20GB存储 / 高清上传'}`,
            '套餐确认',
            {
                confirmButtonText: '确认支付',
                cancelButtonText: '取消',
                type: 'info',
            }
        )
    } catch {
        // 用户点了取消
        return
    }

    upgrading.value = true
    const day = selectedDuration.value === 'month' ? 30 : 365

    try {
        const res = await handleUpdateUserStatus({
            status: pkg.level.toLowerCase(),
            day,
        })

        if (res.code === 200) {
            // 各身份套餐容量上限（字节），与 StorageSet.vue 保持一致
            const GB = 1024 * 1024 * 1024
            const TIER_CAPS: Record<string, number> = {
                normal: 5 * GB,
                vip:    20 * GB,
                svip:   50 * GB,
            }

            // 1. 更新本地 userInfo（先更新身份，再校验容量）
            userInfo.value.accountStatus = pkg.level.toLowerCase() as any
            userInfo.value.membershipDays = (userInfo.value.membershipDays || 0) + day

            // 2. 切换身份后，若当前总容量超过新套餐上限，则将总容量退回至新上限
            //    例：SVIP(50GB) → VIP(20GB)，totalSpace 从 50GB 缩减到 20GB
            const newCap = TIER_CAPS[userInfo.value.accountStatus] ?? TIER_CAPS['normal']
            if (userInfo.value.totalSpace > newCap) {
                const delta = newCap - userInfo.value.totalSpace  // 负数，表示缩减量
                try {
                    await handleAddUserStorage({ size: delta })
                } catch {
                    // API 失败不影响身份切换，仅本地同步
                }
                userInfo.value.totalSpace = newCap
            }

            // 3. 持久化到 storage，确保切换页面后不回退
            storage.set('USER_INFO', userInfo.value)

            // 4. 通知 GlobalHeader / StorageSet / UserMid 等组件同步最新 userInfo
            $bus.emit('userInfoUpdated', userInfo.value)

            ElMessage.success(`${pkg.level}会员开通成功！有效期已延长 ${day} 天`)
        } else {
            ElMessage.error(res.message || '操作失败，请稍后重试')
        }
    } catch {
        ElMessage.error('网络异常，请稍后重试')
    } finally {
        upgrading.value = false
    }
}

// 按钮文字
const getButtonText = (pkg: MemberPackage) => {
    if (userInfo.value.accountStatus.toUpperCase() === pkg.level) return '当前套餐'
    return isMember.value ? '立即切换' : '立即开通'
}
</script>

<style scoped>
#member {
    width: calc(100% - 40px);
    padding: 20px;
    height: calc(100% - 40px);
    position: relative;
}

.member-container {

    width: 100%;
    margin: 0 auto;
    overflow-y: auto;
}

.current-VIP {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 15px;
    padding: 20px;
}

.VIP-icon {
    font-size: 32px;
}

.status-text {
    font-size: 24px;
    font-weight: 500;
}

.expire-time {
    margin-left: auto;
    color: #666;
}





.duration-switch {
    margin-top: 10px;
    margin-bottom: 30px;
    display: flex;
    justify-content: center;
}





.package-list {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
    gap: 30px;

}

.package-card {
    transition: all 0.3s;
    border: 2px solid #e0e0e0;
    cursor: pointer;
    position: relative;
    width: 300px;
    height: 400px;
}

.package-card.active {
    border-color: #409eff;
    transform: translateY(-5px);
    box-shadow: 0 8px 20px rgba(64, 158, 255, 0.2);
}

.package-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 20px;
}

.package-header h3 {
    font-size: 24px;
    margin: 0;
    padding: 0;
}

.price-section {
    text-align: center;

}

.currency {
    font-size: 24px;
    vertical-align: super;
}

.amount {
    font-size: 24px;
    font-weight: bold;
    margin: 0 5px;
}

.duration {
    color: #666;
    font-size: 16px;
}

.privilege-list {
    margin-top: 10px;
}

.privilege-item {
    display: flex;
    align-items: center;
    padding: 12px 0;
    border-bottom: 1px solid #eee;
}

.privilege-item:last-child {
    border: none;
}

.privilege-item .el-icon {
    color: #67c23a;
    margin-right: 10px;
}

.el-button.is-member {
    background: #e0e0e0;
    border-color: #dcdcdc;
    color: #666;
    cursor: not-allowed;
}

@media (max-width: 768px) {
    .package-list {
        grid-template-columns: 1fr;
    }
}
</style>
