<template>
  <AuthStarfield label="注册">
    <RegisterForm :showfoot="true" />
  </AuthStarfield>
</template>

<script setup lang="ts">
import AuthStarfield from '@/components/auth/AuthStarfield.vue'
import RegisterForm from '@/components/auth/RegisterForm.vue'
</script>
