<template>
  <AuthStarfield label="登录">
    <LoginForm :showfoot="true" />
  </AuthStarfield>
</template>

<script setup lang="ts">
import AuthStarfield from '@/components/auth/AuthStarfield.vue'
import LoginForm from '@/components/auth/LoginForm.vue'
</script>
