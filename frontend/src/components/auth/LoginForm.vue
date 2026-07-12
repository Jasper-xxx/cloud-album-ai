<template>
    <div :class="{ 'auth-form': true, 'shareLogin': !showfoot }">
        <div class="form-header">
          <h2 class="auth-title">欢迎回来</h2>
          <p class="auth-subtitle">登录您的 Cloud-Album 账户</p>
        </div>
        <div class="login-type-switch">
            <el-button text @click="toggleLoginType" class="switch-button">
                {{ loginType === 'password' ? '📧 验证码登录' : '🔑 密码登录' }}
            </el-button>
        </div>
        <el-form :model="formData" :rules="rules" ref="loginForm" @submit.prevent="handleSubmit">
            <!-- 邮箱登录 -->
            <el-form-item prop="email" v-if="loginType === 'code'">
                <el-input v-model="formData.email" placeholder="请输入注册邮箱" :prefix-icon="Message" size="large" />
            </el-form-item>
            <!-- 密码登录 -->
            <el-form-item prop="account" v-if="loginType === 'password'">
                <el-input v-model="formData.account" placeholder="请输入用户名" :prefix-icon="User" size="large" />
            </el-form-item>
            <el-form-item prop="password" v-if="loginType === 'password'">
                <el-input v-model="formData.password" type="password" placeholder="请输入密码" :prefix-icon="Lock"
                    size="large" show-password />
            </el-form-item>
            <!-- 验证码登录 -->
            <template v-if="loginType === 'code'">
                <el-form-item prop="code">
                    <div class="code-input">
                        <el-input v-model="formData.code" placeholder="请输入6位验证码" :prefix-icon="Key" size="large" />
                        <el-button type="primary" :disabled="codeCountdown > 0" @click="sendCode" class="code-button">
                            {{ codeButtonText }}
                        </el-button>
                    </div>
                </el-form-item>
            </template>

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
                    {{ loginType === 'password' ? '立即登录' : '验证登录' }}
                </el-button>
            </el-form-item>
        </el-form>

        <div class="auth-footer" v-if="showfoot">
            <span>没有账号？</span>
            <router-link to="/register">立即注册</router-link>
        </div>
    </div>
</template>

<script setup lang="ts">
import { computed, nextTick, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Key, Lock, Message, User } from '@element-plus/icons-vue'
import type { FormInstance, FormRules } from 'element-plus'
import { accountLogin, codeLogin, getEmailCode } from '@/api/user/auth'
import CaptchaVerify from '@/components/auth/CaptchaVerify.vue'
import $bus from '@/utils/bus.ts'
interface Props {
    showfoot: boolean,
}
const props = withDefaults(defineProps<Props>(), {
    showfoot: false,
})
const router = useRouter()
const loginForm = ref<FormInstance>()
const captchaRef = ref<InstanceType<typeof CaptchaVerify>>()
const loading = ref(false)
const codeCountdown = ref(0)
const loginType = ref<'password' | 'code'>('password')
const captchaCode = ref('')

const formData = reactive({
    account: '',
    password: '',
    email: '',
    code: '',
    captchaInput: ''
})

const codeButtonText = computed(() => {
    return codeCountdown.value > 0
        ? `${codeCountdown.value}s后重发`
        : '获取验证码'
})

const rules = reactive<FormRules>({
    account: [
        { required: true, message: '请输入用户名', trigger: 'blur' },
        { min: 4, max: 16, message: '长度在4到16个字符', trigger: 'blur' }
    ],
    password: [
        { required: true, message: '请输入密码', trigger: 'blur' },
        { min: 6, max: 20, message: '长度在6到20个字符', trigger: 'blur' }
    ],
    email: [
        { required: true, message: '请输入邮箱', trigger: 'blur' },
        { type: 'email', message: '请输入正确的邮箱格式', trigger: 'blur' }
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

const toggleLoginType = () => {
    loginType.value = loginType.value === 'password' ? 'code' : 'password'
    // 重置表单
    formData.email = ''
    formData.code = ''
    formData.account = ''
    formData.password = ''
    refreshCaptcha()
}

const sendCode = async () => {
    try {
        await loginForm.value?.validateField(['email', 'captchaInput'])

        codeCountdown.value = 60
        const timer = setInterval(() => {
            codeCountdown.value--
            if (codeCountdown.value <= 0) clearInterval(timer)
        }, 1000)

        // 发送邮箱验证码后不要刷新图形验证码：用户已通过人机校验，刷新会迫使其再输一遍
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
                // 服务端拒绝发信（如邮箱未注册、频控等）也换新图形码，防刷
                refreshCaptcha()
            }
        })

    } catch {
        // 邮箱/图形验证码未通过校验（如图形码输错）时换一张，避免同一图被穷举
        refreshCaptcha()
    }
}

const handleSubmit = async () => {
    try {
        await loginForm.value?.validate()
        loading.value = true
        let promise;

        if (loginType.value === 'code') {
            promise = codeLogin({
                email: formData.email,
                code: formData.code
            });
        }
        else {
            promise = accountLogin({
                account: formData.account,
                password: formData.password
            });
        }
        const resp = await promise;
        if (resp.code == 200) {


            // 存储token
            localStorage.setItem('token', resp.data.token);
            // 登录成功后跳转到首页
            if (props.showfoot) {
                setTimeout(() => {
                    
                    loading.value = false;
                    ElMessage({
                        type: 'success',
                        message: resp.message
                    });
                    refreshCaptcha()
                    router.push('/home');
                }, 500);

            }

            else {
                ElMessage({
                    type: 'success',
                    message: resp.message
                });
                $bus.emit('loginForm', false);
                loading.value = false
                refreshCaptcha()
            }
        }
        else {
            ElMessage({
                type: 'error',
                message: resp.message
            });
            loading.value = false
            // 账号密码错误、邮箱验证码错误等：换新图形码
            refreshCaptcha()
        }

    } catch (error) {
        console.error('登录失败:', error)
        loading.value = false
        // 表单校验失败（含图形验证码错误）或网络异常：换新图形码
        refreshCaptcha()
    } 
}
</script>
<style scoped>
/* 全页模式：无卡片外壳，直接铺满容器 */
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

/* 切换登录方式 */
.login-type-switch {
  position: absolute;
  right: 0;
  top: 4px;
}

.switch-button {
  font-size: 13px;
  color: #6366F1 !important;
  font-weight: 500;
  padding: 6px 10px;
  border-radius: 6px;
}

.switch-button:hover {
  background: #EEF2FF !important;
  color: #4F46E5 !important;
}

/* 表单项间距 */
:deep(.el-form-item) {
  margin-bottom: 16px;
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
  margin-top: 24px;
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
