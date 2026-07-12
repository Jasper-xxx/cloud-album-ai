<template>
    <div :class="{'auth-form':true,'shareLogin':!showfoot}">
        <div class="form-header">
          <h2 class="auth-title">创建账户</h2>
          <p class="auth-subtitle">加入 Cloud-Album 开始您的云相册之旅</p>
        </div>

        <el-form :model="formData" :rules="rules" ref="registerForm" @submit.prevent="handleSubmit">
            <!-- 账号 -->
            <el-form-item prop="account">
                <el-input v-model="formData.account" placeholder="请输入账号" :prefix-icon="User" size="large" />
            </el-form-item>

            <!-- 邮箱 -->
            <el-form-item prop="email">
                <el-input v-model="formData.email" placeholder="请输入邮箱" :prefix-icon="Message" size="large" />
            </el-form-item>

            <!-- 密码 -->
            <el-form-item prop="password">
                <el-input v-model="formData.password" type="password" placeholder="请输入密码" :prefix-icon="Lock"
                    size="large" show-password />
            </el-form-item>

            <!-- 确认密码 -->
            <el-form-item prop="confirmPassword">
                <el-input v-model="formData.confirmPassword" type="password" placeholder="请确认密码" :prefix-icon="Lock"
                    size="large" show-password />
            </el-form-item>

            <!-- 验证码 -->
            <el-form-item prop="code">
                <div class="code-input">
                    <el-input v-model="formData.code" placeholder="请输入6位验证码" :prefix-icon="Key" size="large" />
                    <el-button type="primary" :disabled="codeCountdown > 0" @click="sendCode" class="code-button">
                        {{ codeButtonText }}
                    </el-button>
                </div>
            </el-form-item>

            <el-form-item prop="captchaInput">
                <div class="captcha-input">
                    <el-input
                        v-model="formData.captchaInput"
                        placeholder="请输入图形验证码"
                        :prefix-icon="Key"
                        size="large"
                    />
                    <CaptchaVerify ref="captchaRef" @change="handleCaptchaChange" />
                </div>
            </el-form-item>

            <el-form-item>
                <el-button type="primary" size="large" native-type="submit" class="submit-btn" :loading="loading">
                    立即注册
                </el-button>
            </el-form-item>
        </el-form>

        <div class="auth-footer" v-if="showfoot">
            <span>已有账号？</span>
            <router-link to="/login">立即登录</router-link>
        </div>
    </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Key, Lock, Message, User } from '@element-plus/icons-vue'
import type { FormInstance, FormRules } from 'element-plus'
import { accountRegister, accountLogin, getEmailCode } from '@/api/user/auth'
import CaptchaVerify from '@/components/auth/CaptchaVerify.vue'
import $bus from '@/utils/bus.ts'
interface Props {
    showfoot: boolean,
}
const props = withDefaults(defineProps<Props>(), {
    showfoot: false,
})
const router = useRouter()
const registerForm = ref<FormInstance>()
const captchaRef = ref<InstanceType<typeof CaptchaVerify>>()
const loading = ref(false)
// 验证码剩余时间
const codeCountdown = ref(0)
const captchaCode = ref('')
// 表单
const formData = reactive({
    account: '',
    email: '',
    password: '',
    confirmPassword: '',
    code: '',
    captchaInput: ''
})

const codeButtonText = computed(() => {
    return codeCountdown.value > 0
        ? `${codeCountdown.value}s后重发`
        : '获取验证码'
})

const validatePass2 = (rule: any, value: string, callback: any) => {
    if (value !== formData.password) {
        callback(new Error('两次输入密码不一致!'))
    } else {
        callback()
    }
}

const rules = reactive<FormRules>({
    account: [
        { required: true, message: '请输入账号', trigger: 'blur' },
        { min: 4, max: 16, message: '账号长度为4-16个字符', trigger: 'blur' },
        {
            pattern: /^[a-zA-Z0-9_]+$/,
            message: '账号只能包含字母、数字和下划线',
            trigger: 'blur'
        }
    ],
    email: [
        { required: true, message: '请输入邮箱', trigger: 'blur' },
        { type: 'email', message: '请输入正确的邮箱格式', trigger: 'blur' }
    ],
    password: [
        { required: true, message: '请输入密码', trigger: 'blur' },
        { min: 8, max: 20, message: '密码长度为8-20个字符', trigger: 'blur' },
        {
            pattern: /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d).+$/,
            message: '需包含大小写字母和数字',
            trigger: 'blur'
        }
    ],
    confirmPassword: [
        { required: true, message: '请再次输入密码', trigger: 'blur' },
        { validator: validatePass2, trigger: 'blur' }
    ],
    code: [
        { required: true, message: '请输入验证码', trigger: 'blur' },
        { len: 6, message: '验证码为6位数字', trigger: 'blur' }
    ],
    captchaInput: [
        { required: true, message: '请输入图形验证码', trigger: 'blur' },
        {
            validator: (_rule, value, callback) => {
                if (!value) {
                    callback(new Error('请输入图形验证码'))
                    return
                }
                if (!captchaCode.value) {
                    callback(new Error('验证码加载中，请稍后'))
                    return
                }
                if (value.trim().toLowerCase() !== captchaCode.value.toLowerCase()) {
                    callback(new Error('图形验证码不正确'))
                    return
                }
                callback()
            },
            trigger: 'blur'
        }
    ]
})

const handleCaptchaChange = (value: string) => {
    captchaCode.value = value
}

const refreshCaptcha = async () => {
    formData.captchaInput = ''
    await nextTick()
    captchaRef.value?.refreshCaptcha()
}

const sendCode = async () => {
    try {
        await registerForm.value?.validateField(['email', 'captchaInput'])

        codeCountdown.value = 60
        localStorage.setItem('codeCountdown', codeCountdown.value.toString())
        const timer = setInterval(() => {
            codeCountdown.value--;
            localStorage.setItem('codeCountdown', codeCountdown.value.toString());
            if (codeCountdown.value <= 0) {
                clearInterval(timer)
                localStorage.removeItem('codeCountdown')
            }
        }, 1000)

        // 发送邮箱验证码后不要刷新图形验证码，避免用户重复输入人机验证码
        getEmailCode({ email: formData.email }).then((resp) => {
            if (resp.code == 200) {
                ElMessage({
                    type: 'success',
                    message: resp.message
                });
            }
            else {
                ElMessage({
                    type: 'error',
                    message: resp.message
                });
                refreshCaptcha()
            }
        })

    } catch {
        // 邮箱/图形验证码校验未通过（如图形码输错）时换一张
        refreshCaptcha()
    }
}

onMounted(() => {
    const storedCountdown = localStorage.getItem('codeCountdown')
    if (storedCountdown) {
        codeCountdown.value = parseInt(storedCountdown)
        const timer = setInterval(() => {
            codeCountdown.value--;
            localStorage.setItem('codeCountdown', codeCountdown.value.toString());
            if (codeCountdown.value <= 0) {
                clearInterval(timer)
                localStorage.removeItem('codeCountdown')
            }
        }, 1000)
    }
})

const handleSubmit = async () => {
    try {
        // 验证所有字段
        await registerForm.value?.validate()
        loading.value = true

        // 模拟API调用

        const resp = await accountRegister({
            account: formData.account,
            email: formData.email,
            password: formData.password,
            confirmPassword: formData.confirmPassword,
            code: formData.code
        });
        if (resp.code == 200) {
            // 注册成功后自动登录，无需再到登录页输入一遍
            try {
                const loginResp = await accountLogin({
                    account: formData.account,
                    password: formData.password,
                })
                if (loginResp.code === 200 && loginResp.data?.token) {
                    localStorage.setItem('token', loginResp.data.token)
                    ElMessage.success(loginResp.message || '注册成功，已自动登录')
                    refreshCaptcha()
                    if (props.showfoot) {
                        setTimeout(() => {
                            router.push('/home')
                        }, 400)
                    } else {
                        $bus.emit('registerForm', false)
                    }
                } else {
                    ElMessage.warning(resp.message || '注册成功，请前往登录')
                    refreshCaptcha()
                    if (props.showfoot) router.push('/login')
                    else $bus.emit('registerForm', false)
                }
            } catch {
                ElMessage.warning('注册成功，自动登录失败，请手动登录')
                refreshCaptcha()
                if (props.showfoot) router.push('/login')
                else $bus.emit('registerForm', false)
            }
        }
        else {
            ElMessage({
                type: 'error',
                message: resp.message
            });
            // 含邮箱验证码错误、账号已存在等：换新图形码
            refreshCaptcha()
        }


    } catch (error) {
        console.error('注册失败:', error)
        ElMessage.error('注册失败，请检查表单')
        // 表单校验失败（含图形验证码错误）等：换新图形码
        refreshCaptcha()
    } finally {
        loading.value = false
    }
}
</script>

<style scoped>
/* 全页模式 */
.auth-form {
  width: 100%;
  position: relative;
}

/* 对话框模式（shareLogin）：透明背景 */
.shareLogin {
  background: transparent;
}

/* 表单头部 */
.form-header {
  margin-bottom: 32px;
}

.auth-title {
  font-size: 1.75rem;
  font-weight: 700;
  color: #111827;
  margin: 0 0 6px;
  letter-spacing: -0.4px;
}

.auth-subtitle {
  font-size: 14px;
  color: #6B7280;
  margin: 0;
}

/* 表单项间距 */
:deep(.el-form-item) {
  margin-bottom: 14px;
}

/* 输入框 */
:deep(.el-input__wrapper) {
  background: #F9FAFB;
  border: 1.5px solid #E5E7EB;
  border-radius: 10px;
  box-shadow: none !important;
  transition: all 0.2s;
  height: 44px;
}

:deep(.el-input__wrapper:hover) {
  border-color: #C7D2FE;
  background: #fff;
}

:deep(.el-input__wrapper.is-focus) {
  border-color: #6366F1;
  background: #fff;
  box-shadow: 0 0 0 3px rgba(99, 102, 241, 0.1) !important;
}

:deep(.el-input__prefix) {
  color: #9CA3AF;
}

:deep(.el-input__inner) {
  font-size: 14px;
  color: #111827;
}

:deep(.el-input__inner::placeholder) {
  color: #9CA3AF;
}

/* 验证码行 */
.code-input {
  display: flex;
  gap: 10px;
  align-items: center;
}

.captcha-input {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  width: 100%;
}

.code-input .el-input {
  flex: 1;
}

.captcha-input .el-input {
  flex: 1 1 160px;
  min-width: 140px;
}

.code-button {
  height: 44px;
  min-width: 110px;
  border-radius: 10px;
  background: #6366F1;
  border-color: #6366F1;
  font-size: 13px;
  transition: all 0.2s;
}

.code-button:hover:not(:disabled) {
  background: #4F46E5;
  border-color: #4F46E5;
  transform: translateY(-1px);
}

/* 提交按钮 */
.submit-btn {
  width: 100%;
  height: 44px;
  margin-top: 6px;
  border-radius: 10px;
  background: #6366F1;
  border-color: #6366F1;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.3px;
  transition: all 0.2s;
}

.submit-btn:hover {
  background: #4F46E5;
  border-color: #4F46E5;
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(99, 102, 241, 0.35);
}

/* 页脚跳转链接 */
.auth-footer {
  text-align: center;
  color: #9CA3AF;
  margin-top: 20px;
  font-size: 14px;
}

.auth-footer a {
  color: #6366F1;
  text-decoration: none;
  font-weight: 600;
  transition: color 0.2s;
}

.auth-footer a:hover {
  color: #4F46E5;
  text-decoration: underline;
}
</style>
