#ifndef HAISHINKIT_KT_DYNAMICLOADER_H
#define HAISHINKIT_KT_DYNAMICLOADER_H

namespace Graphics {
    class DynamicLoader {
    public:
        DynamicLoader(const DynamicLoader &) = delete;

        DynamicLoader &operator=(const DynamicLoader &) = delete;

        DynamicLoader(DynamicLoader &&) = delete;

        DynamicLoader &operator=(DynamicLoader &&) = delete;

        bool Load();

        static DynamicLoader &GetInstance() {
            static DynamicLoader instance;
            return instance;
        }

    private:
        DynamicLoader() = default;

        ~DynamicLoader() = default;

        bool loaded = false;
    };
}

#endif //HAISHINKIT_KT_DYNAMICLOADER_H
