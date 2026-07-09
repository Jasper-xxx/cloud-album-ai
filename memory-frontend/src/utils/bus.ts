import mitt from 'mitt';
import type { SmartSelectConfig } from '@/utils/similarSmartSelect';

// 定义事件类型
interface Events {
  // 用户信息相关
  userInfoUpdated: API.UserInfo;
  avatarConfirmed: string;
  
  // 登录注册相关
  loginForm: boolean;
  registerForm: boolean;
  login: void;
  
  // 图片选择相关
  addSelectPicture: any;
  showUploadList: any;
  
  // 图片操作相关
  uploadSucess: number;
  loadMoreData: void;
  deletePictureSuceess: void;
  removePictureSuceess: void;
  removePictureSuccess: void;
  dropPictureSuceess: void;
  recoverPictureSuceess: void;
  
  // 界面交互相关
  toggleMobileSidebar: void;
  showDrawer: boolean;
  setCurrentFileId: string;
  IsAutoSelect: SmartSelectConfig;
  IsLoading: boolean;
  smartSelectApplied: {
    success: boolean;
    strategy: SmartSelectConfig['strategy'];
    retainCount: number;
    selectedCount: number;
    message?: string;
  };
  
  // 组件关闭相关
  closeAddAlbum: boolean;
  closePictureTag: boolean;
  closeSharePicture: void;
  
  // 索引签名，允许任何字符串或symbol类型的事件名称
  [key: string]: any;
  [key: symbol]: any;
}

const $bus = mitt<Events>();
export default $bus;
