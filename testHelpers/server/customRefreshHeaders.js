class CustomRefreshAPIHeaders{
    static containsCustomRefreshHeaders = false;  

    static resetCustomRefreshAPIHeaders(){
        CustomRefreshAPIHeaders.containsCustomRefreshHeaders = false;
    }

    static setCustomRefreshAPIHeaders(value){
        CustomRefreshAPIHeaders.containsCustomRefreshHeaders = value;
    }

    static getCustomRefreshAPIHeaders() {
        return CustomRefreshAPIHeaders.containsCustomRefreshHeaders;
    }
}

module.exports = CustomRefreshAPIHeaders;