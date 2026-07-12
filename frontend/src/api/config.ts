/**
 * 公共配置
 */
const defaultBaseUrl = import.meta.env.DEV ? '/devApi' : '/api';

const requestPublicConfig = {
  baseUrl: import.meta.env.VITE_BACKEND_API || defaultBaseUrl,
  timeout: 100000,
  headers: {
    Accept: 'application/json, text/plain, */*',
    'Content-Type': 'application/json',
    'X-Requested-With': 'XMLHttpRequest'
  },
};

export default requestPublicConfig;